#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <atomic>
#include <vector>
#include <algorithm>
#include <unistd.h>
#include <sys/sysinfo.h>
#include <pthread.h>
#include "whisper.h"

#ifdef __ARM_NEON
#include <arm_neon.h>
#define HAS_NEON 1
#else
#define HAS_NEON 0
#endif

static whisper_context* g_ctx = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_loaded{false};
static int g_optimal_threads = 4;
static std::vector<int> g_perf_core_ids;

static int detect_perf_cores() {
    int total = sysconf(_SC_NPROCESSORS_CONF);
    if (total <= 0) return 4;
    g_perf_core_ids.clear();
    return std::min(std::max(total / 2, 2), 6);
}

static void convert_i16_to_f32(const int16_t* src, float* dst, size_t count) {
#if HAS_NEON
    const float scale = 1.0f / 32768.0f;
    const float32x4_t vscale = vdupq_n_f32(scale);
    size_t i = 0;
    for (; i + 7 < count; i += 8) {
        int16x8_t v_i16 = vld1q_s16(src + i);
        int32x4_t v_i32_lo = vmovl_s16(vget_low_s16(v_i16));
        int32x4_t v_i32_hi = vmovl_s16(vget_high_s16(v_i16));
        float32x4_t v_f32_lo = vmulq_f32(vcvtq_f32_s32(v_i32_lo), vscale);
        float32x4_t v_f32_hi = vmulq_f32(vcvtq_f32_s32(v_i32_hi), vscale);
        vst1q_f32(dst + i, v_f32_lo);
        vst1q_f32(dst + i + 4, v_f32_hi);
    }
    for (; i < count; i++) dst[i] = static_cast<float>(src[i]) / 32768.0f;
#else
    for (size_t i = 0; i < count; i++) dst[i] = static_cast<float>(src[i]) / 32768.0f;
#endif
}

// Trim leading and trailing silence so whisper.cpp doesn't hallucinate
// filler tokens (e.g. "Message", "Thank you") on the silent tail/head.
// Returns false if the entire buffer is silence (caller should emit empty).
static bool trim_silence(std::vector<float>& pcm, int sample_rate) {
    const int frame = sample_rate / 100;  // 10ms frames
    if ((int)pcm.size() < frame) return true;

    const size_t n_frames = pcm.size() / frame;
    std::vector<float> rms(n_frames, 0.0f);
    float max_rms = 0.0f;
    for (size_t f = 0; f < n_frames; f++) {
        double sum = 0.0;
        const float* base = pcm.data() + f * frame;
        for (int i = 0; i < frame; i++) {
            float s = base[i];
            sum += (double)s * s;
        }
        rms[f] = (float)std::sqrt(sum / frame);
        if (rms[f] > max_rms) max_rms = rms[f];
    }

    // Adaptive threshold: 8% of peak, with a floor to catch very quiet speech.
    const float threshold = std::max(0.004f, max_rms * 0.08f);

    int start_frame = -1;
    int end_frame = -1;
    for (size_t f = 0; f < n_frames; f++) {
        if (rms[f] > threshold) { start_frame = (int)f; break; }
    }
    for (size_t f = n_frames; f-- > 0;) {
        if (rms[f] > threshold) { end_frame = (int)f; break; }
    }

    if (start_frame < 0 || end_frame < 0 || end_frame < start_frame) {
        // Entire buffer is silence — nothing to transcribe.
        return false;
    }

    // Pad slightly so speech isn't clipped at the edges.
    const int pad = 10;  // 100ms
    int start = std::max(0, start_frame - pad) * frame;
    int end = std::min((int)pcm.size(), (end_frame + 1 + pad) * frame);
    pcm = std::vector<float>(pcm.begin() + start, pcm.begin() + end);
    return true;
}


extern "C" {

JNIEXPORT jlong JNICALL
Java_com_taptype_taptypepro_engine_WhisperEngine_nativeLoadModel(
    JNIEnv* env, jobject /* this */, jstring modelPath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    g_ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (g_ctx) {
        g_loaded.store(true);
        g_optimal_threads = std::max(2, std::min(nThreads, detect_perf_cores()));
        return reinterpret_cast<jlong>(g_ctx);
    }
    g_loaded.store(false);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_taptype_taptypepro_engine_WhisperEngine_nativeTranscribe(
    JNIEnv* env, jobject /* this */, jlong ptr, jfloatArray samples) {
    if (!g_loaded.load() || ptr == 0) return env->NewStringUTF("");
    auto* ctx = reinterpret_cast<whisper_context*>(ptr);
    std::lock_guard<std::mutex> lock(g_mutex);

    jsize len = env->GetArrayLength(samples);
    jfloat* raw = env->GetFloatArrayElements(samples, nullptr);
    std::vector<float> pcmf32(raw, raw + len);
    env->ReleaseFloatArrayElements(samples, raw, JNI_ABORT);

    // Drop leading/trailing silence to avoid hallucinated filler tokens.
    if (!trim_silence(pcmf32, WHISPER_SAMPLE_RATE)) {
        return env->NewStringUTF("");
    }
    if (pcmf32.size() < WHISPER_SAMPLE_RATE / 4) {
        // Too short to contain meaningful speech.
        return env->NewStringUTF("");
    }

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = g_optimal_threads;
    wparams.no_context = true;
    wparams.single_segment = true;
    // Kill the "Message"/"Thank you" hallucination on silence (issues #1724, #1592):
    //  - no_timestamps: don't COMPUTE timestamps (not just hide them). Timestamp
    //    decoding is the main driver of whisper.cpp hallucination; disabling it
    //    gives a ~4x WER reduction and removes the phantom filler tokens.
    //  - suppress_nst: suppress non-speech tokens ("Message", "Thank you", etc.).
    wparams.no_timestamps = true;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;
    wparams.temperature_inc = 0.0f;

    if (whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        return env->NewStringUTF("");
    }

    const int n_segments = whisper_full_n_segments(ctx);
    std::string result;
    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text && strlen(text) > 0) {
            if (!result.empty()) result += " ";
            result += text;
        }
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_taptype_taptypepro_engine_WhisperEngine_nativeRelease(
    JNIEnv* /* env */, jobject /* this */, jlong ptr) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx && reinterpret_cast<jlong>(g_ctx) == ptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    g_loaded.store(false);
}

JNIEXPORT jboolean JNICALL
Java_com_taptype_taptypepro_engine_WhisperEngine_nativeHasNEON(
    JNIEnv* /* env */, jobject /* this */) {
    return HAS_NEON ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_taptype_taptypepro_engine_WhisperEngine_nativeHasKleidiAI(
    JNIEnv* /* env */, jobject /* this */) {
#ifdef GGML_USE_CPU_KLEIDIAI
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

} // extern "C"

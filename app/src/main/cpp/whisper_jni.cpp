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
    wparams.suppress_blank = true;
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

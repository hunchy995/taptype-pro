cmake_minimum_required(VERSION 3.22.1)
project("taptypepro_whisper" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -ffast-math -fno-finite-math-only -funroll-loops -fomit-frame-pointer")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -ffast-math -fno-finite-math-only -funroll-loops -fomit-frame-pointer")

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
endif()

set(WHISPER_DIR /opt/data/whisper.cpp)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

set(WHISPER_VERSION "1.7.4")

add_library(whisper SHARED ${WHISPER_DIR}/src/whisper.cpp whisper_jni.cpp)

target_include_directories(whisper PRIVATE
    ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src ${WHISPER_DIR}/ggml/src/ggml-cpu ${WHISPER_DIR})

target_link_libraries(whisper log android ggml)
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
target_compile_options(whisper PRIVATE -flto=thin)

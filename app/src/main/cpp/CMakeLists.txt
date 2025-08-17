cmake_minimum_required(VERSION 3.22)
project(espeakng_jni)

add_library(espeakng_jni SHARED espeakng_jni.c)

# Attempt to link against a prebuilt eSpeak NG shared library if present.
find_library(espeak_ng_lib NAMES espeak-ng PATHS ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a)
if(espeak_ng_lib)
    target_link_libraries(espeakng_jni ${espeak_ng_lib} log)
else()
    message(WARNING "eSpeak-NG library not found; building stub JNI only")
    target_link_libraries(espeakng_jni log)
endif()

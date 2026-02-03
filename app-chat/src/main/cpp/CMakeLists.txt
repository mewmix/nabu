cmake_minimum_required(VERSION 3.22.1)
project(llama_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Llama.cpp build options
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_COMMON OFF)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/llama.cpp)
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-build)

add_library(llama_jni SHARED
    llama_jni.cpp
)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_DIR}/include
)

target_link_libraries(llama_jni
    llama
    android
    log
)

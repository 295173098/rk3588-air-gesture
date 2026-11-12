cmake_minimum_required(VERSION 3.22.1)
project("gesture_npu" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -fexceptions -frtti")

# RKNN runtime 头文件
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/rknn_api)

# 预编译的 librknnrt.so（位于 app/libs/arm64-v8a）
set(RKNN_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/${ANDROID_ABI})
add_library(rknnrt SHARED IMPORTED)
set_target_properties(rknnrt PROPERTIES IMPORTED_LOCATION ${RKNN_LIB_DIR}/librknnrt.so)

add_library(gesture_npu SHARED
        gesture_npu.cpp
        virtual_mouse.cpp)

find_library(log-lib log)

target_link_libraries(gesture_npu
        rknnrt
        ${log-lib}
        android
        jnigraphics)

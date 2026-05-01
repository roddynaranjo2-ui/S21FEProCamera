cmake_minimum_required(VERSION 3.22.1)
project("procamera")

set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../opencv/sdk/native/jni")
find_package(OpenCV REQUIRED)

add_library(
        procamera
        SHARED
        native-lib.cpp
        hdr_processor.cpp
        image_processor.cpp
)

find_library(
        log-lib
        log
)

include_directories(${OpenCV_INCLUDE_DIRS})

target_link_libraries(
        procamera
        ${log-lib}
        ${OpenCV_LIBS}
)

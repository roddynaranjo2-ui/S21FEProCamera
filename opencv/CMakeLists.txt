cmake_minimum_required(VERSION 3.10)
project(opencv_module)

# Set the directory where OpenCVConfig.cmake is located
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/sdk/native/jni")

# Find OpenCV package
find_package(OpenCV REQUIRED)

# Include OpenCV headers
include_directories(${OpenCV_INCLUDE_DIRS})

# Add a dummy library to satisfy Gradle's requirement for a native build in this module
add_library(opencv_dummy SHARED src/main/cpp/dummy.cpp)

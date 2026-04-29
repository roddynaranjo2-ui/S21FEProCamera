cmake_minimum_required(VERSION 3.22.1)

project("procamera")

add_library(
        procamera
        SHARED
        native-lib.cpp
        hdr_processor.cpp
        denoise_processor.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        procamera
        ${log-lib}
)

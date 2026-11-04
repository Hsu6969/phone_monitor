cmake_minimum_required(VERSION 3.22.1)
project(uvcstream C)

add_library(uvcstream SHARED uvc_stream.c)
target_link_libraries(uvcstream log)

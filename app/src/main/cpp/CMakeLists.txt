cmake_minimum_required(VERSION 3.22.1)
project(engine C)
add_library(cxx_shared SHARED native.c)
set_target_properties(cxx_shared PROPERTIES OUTPUT_NAME "c++_shared" PREFIX "lib")

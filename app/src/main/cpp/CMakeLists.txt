cmake_minimum_required(VERSION 3.22.1)
project("mcpatch")

add_library(
    mcpatch
    SHARED
    mcpatch.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    mcpatch
    ${log-lib}
)

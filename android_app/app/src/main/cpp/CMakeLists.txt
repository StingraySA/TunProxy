# File location: app/src/main/cpp/CMakeLists.txt   ← must be exactly here

cmake_minimum_required(VERSION 3.22.1)
project(tun2http LANGUAGES C)

# This works on ALL Android NDK versions (22 → 29)
file(GLOB SOURCES "*.c")

# If you ever add subfolders later, just add another line like:
# file(GLOB SUB_SOURCES "subfolder/*.c")
# list(APPEND SOURCES ${SUB_SOURCES})

add_library(tun2http SHARED ${SOURCES})

find_library(log-lib log)
target_link_libraries(tun2http ${log-lib})

# Optional but nice
target_compile_options(tun2http PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden
)
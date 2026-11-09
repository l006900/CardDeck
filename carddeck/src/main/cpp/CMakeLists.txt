cmake_minimum_required(VERSION 3.22.1)
project(arcana_frameclock LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG -fvisibility=hidden")

add_library(arcana_frameclock SHARED frameclock.cpp gputimer.cpp)

# Android 15+ 的 16KB 页设备要求 ELF LOAD 段按 0x4000 对齐；lld 默认仍是 4KB，
# 未对齐时系统会对可调试应用弹「应用兼容性」警告，且 16KB 内核上可能加载失败。
target_link_options(arcana_frameclock PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# gputimer.cpp 通过 eglGetProcAddress 解析 GL_EXT_disjoint_timer_query，
# 需要链接 EGL；glGetString / glGetIntegerv 等 ES2 入口需要 GLESv2。
target_link_libraries(arcana_frameclock EGL GLESv2)

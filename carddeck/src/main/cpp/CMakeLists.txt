cmake_minimum_required(VERSION 3.22.1)
project(arcana_frameclock LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG -fvisibility=hidden")

add_library(arcana_frameclock SHARED frameclock.cpp gputimer.cpp)

# gputimer.cpp 通过 eglGetProcAddress 解析 GL_EXT_disjoint_timer_query，
# 需要链接 EGL；glGetString / glGetIntegerv 等 ES2 入口需要 GLESv2。
target_link_libraries(arcana_frameclock EGL GLESv2)

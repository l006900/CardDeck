cmake_minimum_required(VERSION 3.22.1)
project(arcana_frameclock LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG -fvisibility=hidden")

add_library(arcana_frameclock SHARED frameclock.cpp)

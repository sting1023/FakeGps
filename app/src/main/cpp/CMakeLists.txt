cmake_minimum_required(VERSION 3.18.1)

project("fakegps")

# Common flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Wall -Wextra -fPIC")

# Sources
add_library(fakegps SHARED fake_gps_hook.cpp)

# Links
target_link_libraries(fakegps
    android
    log
)

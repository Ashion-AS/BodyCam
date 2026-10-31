cmake_minimum_required(VERSION 3.18.1)
project(bodycam CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-Wall -Wextra -Wno-unused-parameter -Wno-unused-variable)
add_definitions(-DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES)

add_library(bodycam SHARED
        jni_bridge.cpp
        renderer.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(EGL-lib EGL)
find_library(GLES-lib GLESv2)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(bodycam
        ${log-lib}
        ${android-lib}
        ${EGL-lib}
        ${GLES-lib}
        ${jnigraphics-lib}
        z
        m)

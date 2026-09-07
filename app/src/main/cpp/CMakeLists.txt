cmake_minimum_required(VERSION 3.22.1)
project("ArcArAndroid")

# ---------------------------------------------------------------------------
# ArcAr physics library (unmodified upstream sources, just relocated on disk)
# ---------------------------------------------------------------------------
set(ARCAR_SRC_DIR    "${CMAKE_CURRENT_SOURCE_DIR}/ArcArLib/src")
set(ARCAR_LIBSRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ArcArLib/libsrc")

file(GLOB_RECURSE FILES_ARCAR
    "${ARCAR_SRC_DIR}/*.cpp"
    "${ARCAR_SRC_DIR}/*.h"
)
file(GLOB_RECURSE FILES_BULLET
    "${ARCAR_LIBSRC_DIR}/bullet3-3.24/*.cpp"
    "${ARCAR_LIBSRC_DIR}/bullet3-3.24/*.h"
)

add_library(ArcArCore STATIC ${FILES_ARCAR} ${FILES_BULLET})

target_include_directories(ArcArCore PUBLIC
    "${ARCAR_SRC_DIR}"
    "${ARCAR_LIBSRC_DIR}/bullet3-3.24"
)

set_target_properties(ArcArCore PROPERTIES
    LINKER_LANGUAGE CXX
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

# ArcAr/Bullet expect exceptions + RTTI (already passed globally via
# app/build.gradle cppFlags, kept here too so this target builds standalone).
target_compile_options(ArcArCore PRIVATE -frtti -fexceptions)

# ---------------------------------------------------------------------------
# JNI bridge: the only Android-specific code in this project.
# ---------------------------------------------------------------------------
add_library(arcar_jni SHARED jni_bridge.cpp)

find_library(log-lib log)

target_link_libraries(arcar_jni
    ArcArCore
    ${log-lib}
)

target_include_directories(arcar_jni PRIVATE "${ARCAR_SRC_DIR}")

set_target_properties(arcar_jni PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
)

cmake_minimum_required(VERSION 3.10)
project(uinput-jni)

# Set C standard
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Create the native library
add_library(uinput-jni SHARED uinput-jni.c)

# Link against Android log library
find_library(log-lib log)
target_link_libraries(uinput-jni ${log-lib})

# Set the output directory
set_target_properties(uinput-jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/libs/${CMAKE_ANDROID_ARCH_ABI}"
)

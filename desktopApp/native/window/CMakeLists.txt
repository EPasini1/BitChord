cmake_minimum_required(VERSION 3.16)

project(bitchord_window LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

add_library(bitchord_window SHARED window_jni.cpp)
set_target_properties(bitchord_window PROPERTIES PREFIX "")

if(DEFINED ENV{JAVA_HOME})
    target_include_directories(bitchord_window PRIVATE
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/win32
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(bitchord_window PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_link_libraries(bitchord_window PRIVATE user32 dwmapi ole32 uuid)

include(CTest)
if(BUILD_TESTING)
    add_executable(window_audio_tests window_audio_tests.cpp)
    set_property(SOURCE window_audio_tests.cpp PROPERTY OBJECT_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/window_jni.cpp")
    get_target_property(window_jni_includes bitchord_window INCLUDE_DIRECTORIES)
    target_include_directories(window_audio_tests PRIVATE ${window_jni_includes})
    target_link_libraries(window_audio_tests PRIVATE user32 dwmapi ole32 uuid)
    add_test(NAME wasapi_stream_recovery COMMAND window_audio_tests)
    set_tests_properties(wasapi_stream_recovery PROPERTIES TIMEOUT 15)
endif()

if(MINGW)
    # The desktop app loads this DLL from its private runtime directory. Gradle daemons and
    # installed builds do not inherit the developer toolchain's bin directory, so leaving MinGW's
    # libwinpthread dynamic makes the bridge silently unavailable even though the DLL itself was
    # packaged correctly.
    target_link_options(bitchord_window PRIVATE -static -static-libgcc -static-libstdc++)
    if(BUILD_TESTING)
        target_link_options(window_audio_tests PRIVATE -static -static-libgcc -static-libstdc++)
    endif()
endif()

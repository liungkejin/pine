cmake_minimum_required(VERSION 3.22)
project(pine_initialization_regression C CXX)
set(CMAKE_CXX_STANDARD 17)
set(PINE_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../core/src/main/cpp" CACHE PATH "Pine native sources")
add_library(pine_elf_fixture SHARED ElfFixture.cpp)
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    # Execute-only text is still a valid function-symbol target (Android 10).
    target_link_options(pine_elf_fixture PRIVATE "-Wl,--rosegment" "-Wl,--execute-only")
endif()
add_executable(pine_elf_regression ElfImageTest.cpp
    "${PINE_SOURCE}/utils/elf_image.cpp"
    "${PINE_SOURCE}/external/xz-embedded/linux/lib/xz/xz_crc32.c"
    "${PINE_SOURCE}/external/xz-embedded/linux/lib/xz/xz_dec_lzma2.c"
    "${PINE_SOURCE}/external/xz-embedded/linux/lib/xz/xz_dec_stream.c")
target_include_directories(pine_elf_regression PRIVATE "${PINE_SOURCE}"
    "${PINE_SOURCE}/external/xz-embedded/linux/include/linux"
    "${PINE_SOURCE}/external/xz-embedded/userspace")
target_link_libraries(pine_elf_regression PRIVATE log dl)
target_link_options(pine_elf_regression PRIVATE "-Wl,--wrap=dlsym")
add_library(pine_init_probe SHARED InitializationProbe.cpp)
target_link_libraries(pine_init_probe PRIVATE dl)
enable_testing()
foreach(scenario normal readonly_alias executable_alias file_mapping duplicate_handles concurrent
        legacy_normal legacy_readonly_alias legacy_file_mapping basename_normal basename_readonly_alias)
    add_test(NAME pine.elf.${scenario} COMMAND pine_elf_regression ${scenario} $<TARGET_FILE:pine_elf_fixture>)
    set_tests_properties(pine.elf.${scenario} PROPERTIES TIMEOUT 30)
endforeach()

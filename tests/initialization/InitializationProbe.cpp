#include <jni.h>
#include <fcntl.h>
#include <link.h>
#include <cstring>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" JNIEXPORT void JNICALL
Java_top_canyie_pine_regression_InitializationTest_mapArtFile(JNIEnv* env, jclass, jint mode) {
    struct Library { const char* path = nullptr; uintptr_t base = 0; } library;
    dl_iterate_phdr([](dl_phdr_info* info, size_t, void* data) {
        auto* library = static_cast<Library*>(data);
        const char* filename = std::strrchr(info->dlpi_name, '/');
        if (std::strcmp(filename ? filename + 1 : info->dlpi_name, "libart.so") != 0) return 0;
        library->path = info->dlpi_name;
        library->base = info->dlpi_addr;
        return 1;
    }, &library);
    if (!library.path) {
        env->ThrowNew(env->FindClass("java/lang/AssertionError"), "ART not loaded");
        return;
    }
    int fd = open(library.path, O_RDONLY | O_CLOEXEC);
    struct stat st{};
    if (fd < 0 || fstat(fd, &st) != 0) {
        if (fd >= 0) close(fd);
        env->ThrowNew(env->FindClass("java/lang/AssertionError"), "cannot open ART file");
        return;
    }
    const size_t size = mode == 2 ? static_cast<size_t>(sysconf(_SC_PAGESIZE)) : st.st_size;
    void* mapping = mmap(reinterpret_cast<void*>(0x40000000), size,
                        PROT_READ | (mode == 1 ? PROT_EXEC : 0), MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapping == MAP_FAILED || reinterpret_cast<uintptr_t>(mapping) >= library.base) {
        env->ThrowNew(env->FindClass("java/lang/AssertionError"), "ART alias must precede loaded ART");
    }
    // This process-lifetime test mapping deliberately remains present while Pine initializes.
}

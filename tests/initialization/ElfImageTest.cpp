#include "android.h"
#include "utils/elf_image.h"
#include <atomic>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

// The standalone resolver test does not initialize ART or link the rest of Pine.
int pine::Android::version = 36;

// Linker-boundary injection stays entirely in the regression executable.
// Address and relocation assertions still use the real dlopen/dlsym results.
static bool legacyLinker = false;
static bool basenameLinker = false;
extern "C" void* __real_dlsym(void*, const char*);
static int iterateBasenames(int (*callback)(dl_phdr_info*, size_t, void*), void* data) {
    struct Call { int (*callback)(dl_phdr_info*, size_t, void*); void* data; } call{callback, data};
    return dl_iterate_phdr([](dl_phdr_info* info, size_t size, void* opaque) {
        auto* call = static_cast<Call*>(opaque);
        dl_phdr_info copy = *info;
        const char* slash = std::strrchr(copy.dlpi_name, '/');
        if (slash) copy.dlpi_name = slash + 1;
        return call->callback(&copy, size, call->data);
    }, &call);
}
extern "C" void* __wrap_dlsym(void* handle, const char* name) {
    if (std::strcmp(name, "dl_iterate_phdr") == 0) {
        if (legacyLinker) return nullptr;
        if (basenameLinker) return reinterpret_cast<void*>(iterateBasenames);
    }
    return __real_dlsym(handle, name);
}

static void require(bool condition, const char* message) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::exit(1);
    }
}

int main(int argc, char** argv) {
    require(argc == 3, "usage: pine_elf_regression scenario /path/to/fixture.so");
    const char* scenario = argv[1];
    const char* path = argv[2];
    legacyLinker = std::strncmp(scenario, "legacy_", 7) == 0;
    basenameLinker = std::strncmp(scenario, "basename_", 9) == 0;
    if (legacyLinker) scenario += 7;
    if (basenameLinker) scenario += 9;
    const bool fileOnly = std::strcmp(scenario, "file_mapping") == 0;
    void* library = fileOnly ? nullptr : dlopen(path, RTLD_NOW | RTLD_LOCAL);
    require(fileOnly || library, "dlopen failed");
    void* expected = fileOnly ? nullptr : dlsym(library, "pine_fixture_pointer");
    require(fileOnly || expected, "fixture symbol missing");
    void* mapping = MAP_FAILED;
    struct stat st{};
    if (fileOnly || std::strcmp(scenario, "readonly_alias") == 0 ||
            std::strcmp(scenario, "executable_alias") == 0) {
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        require(fd >= 0 && fstat(fd, &st) == 0, "cannot open fixture");
        const int protection = PROT_READ |
                (std::strcmp(scenario, "executable_alias") == 0 ? PROT_EXEC : 0);
        // A non-fixed low hint leaves existing mappings untouched. Reproduce a
        // raw file mapping sorted before the real linker mapping in /proc/maps.
        mapping = mmap(reinterpret_cast<void*>(0x40000000), st.st_size,
                       protection, MAP_PRIVATE, fd, 0);
        close(fd);
        require(mapping != MAP_FAILED, "cannot map fixture file");
        require(fileOnly || reinterpret_cast<uintptr_t>(mapping) < reinterpret_cast<uintptr_t>(expected),
                "test mapping must precede the loaded library");
    }
    if (fileOnly) {
        pine::ElfImage image(path);
        require(!image.IsOpened() && image.GetSymbolAddress("pine_fixture_pointer", false) == nullptr,
                "a file mapping was accepted as a loaded library");
    } else if (std::strcmp(scenario, "concurrent") == 0) {
        std::atomic<bool> valid{true};
        std::vector<std::thread> threads;
        for (int i = 0; i < 8; ++i) {
            threads.emplace_back([&]() {
                for (int j = 0; j < 100; ++j) {
                    pine::ElfImage image(path);
                    if (image.GetSymbolAddress("pine_fixture_pointer") != expected) valid = false;
                }
            });
        }
        for (auto& thread : threads) thread.join();
        require(valid, "concurrent lookups selected a file mapping");
    } else {
        pine::ElfImage image(path);
        require(image.IsOpened(), "loaded fixture was rejected");
        void* resolved = image.GetSymbolAddress("pine_fixture_pointer");
        std::printf("resolved=%p linker=%p file_mapping=%p\n", resolved, expected, mapping);
        require(resolved == expected, "resolved address differs from the dynamic linker");
        require(**static_cast<int**>(resolved) == 42, "relocated pointer value is incorrect");
        auto function = reinterpret_cast<int (*)()>(image.GetSymbolAddress("pine_fixture_function"));
        require(function && function() == 42, "function symbol resolves incorrectly");
        require(image.GetSymbolAddress("pine_missing_symbol", false) == nullptr, "missing symbol accepted");
        if (std::strcmp(scenario, "duplicate_handles") == 0) {
            pine::ElfImage second(path);
            require(second.GetSymbolAddress("pine_fixture_pointer") == expected,
                    "second resolver selected the first resolver's file mapping");
        }
    }
    if (mapping != MAP_FAILED) munmap(mapping, st.st_size);
    if (library) dlclose(library);
    std::printf("PASS %s\n", argv[1]);
}

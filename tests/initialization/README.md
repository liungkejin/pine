# Pine initialization regression

`ElfImage` must resolve symbols in the library loaded by the dynamic linker.
A separate read-only `mmap` of the same file has no relocations and must never
be used as the load address. This matters for `art::Runtime::instance_`: a wrong
address can either be unmapped or contain an invalid Runtime pointer.

The original Paji arm64 library, Build ID
`d89693db76971b9b98c733597f93c025b93645ac`, crashes at `0x9b74` in
`Android::InitMembersFromRuntime` when the full ART file is mapped below the
loaded ART image. This was reproduced on Android 16 with the unmodified library.
The same production reports also contain `0x9aac`, the preceding Runtime global
pointer dereference. Its exact crash offset was not separately reproduced.

## Build the production library

Use JDK 17 and the project's pinned NDK 25.2.9519653:

```sh
./gradlew :core:assembleRelease
```

This retains API 19 for armeabi-v7a, API 21 for arm64-v8a, and 16 KB ELF load
segment alignment. The tests do not alter production compile options.

## Build the isolated test APK

The script accepts either the new core AAR or a consumer AAR whose native
libraries were updated. It packages the supplied AAR's Java classes and native
libraries unchanged in a dedicated `top.canyie.pine.regression` test app.

```sh
python3 tests/initialization/build_test_apk.py \
  --sdk "$ANDROID_SDK_ROOT" \
  --aar core/build/outputs/aar/core-release.aar \
  --abi arm64-v8a --out /tmp/pine-initialization-arm64
adb -s DEVICE install /tmp/pine-initialization-arm64/pine-test.apk
for scenario in normal readonly_alias executable_alias header_alias default_hooks; do
  adb -s DEVICE shell am instrument -w -e scenario "$scenario" \
    top.canyie.pine.regression/.RegressionInstrumentation
done
adb -s DEVICE uninstall top.canyie.pine.regression
```

Every scenario uses a fresh app process and checks initialization twice, an
actual Java method hook, and restoration of the original result after unhook.
`default_hooks` enables the default hidden-API policy configuration. Alias cases
isolate Runtime initialization from hidden-API patching. `header_alias` maps only
the first ART file page; the other alias cases map the entire file. The mappings
use a non-fixed low address hint and never overwrite an existing mapping.

Use `--abi armeabi-v7a` and a different output directory for a 32-bit test APK.
The test APK has its own generated test signing key and does not replace Paji.

## Native resolver cases

The same build produces `native/pine_elf_regression` and
`native/libpine_elf_fixture.so`. Push them to a temporary device directory and
run the executable with `SCENARIO /absolute/device/path/libpine_elf_fixture.so`.
CTest registers these cases for environments with a configured Android runner:

- `normal`, `readonly_alias`, `executable_alias`, `file_mapping`
- `duplicate_handles`, `concurrent` (8 threads, 100 lookups each)
- `legacy_normal`, `legacy_readonly_alias`, `legacy_file_mapping`
- `basename_normal`, `basename_readonly_alias`

The fixture contains a relocated data pointer and a callable function. Resolver
addresses must match the real `dlsym` results. arm64 also uses execute-only text.
Legacy and basename cases inject linker availability/naming at the test link
boundary using `--wrap=dlsym`; they are not claims of testing an old Android OS.
No test hooks or test-specific state are compiled into the production library.

## Verification on 2026-11-13

- Unmodified resolver: wrong addresses for read-only/executable file aliases,
  and incorrectly accepts a file mapping without a loaded library.
- Unmodified production Pine: full ART file alias reproduces `SIGSEGV` at `0x9b74`.
- Final resolver: all 11 cases pass on Samsung SM-S9370, Android 16/API 36, arm64,
  4 KB pages.
- Final Paji AAR: all 5 isolated APK cases pass on that device.
- Both production ABIs and both test APK ABIs build. 32-bit device execution,
  Android 4.4/5 devices, and a physical 16 KB-page device were not available for
  this validation; 16 KB ELF alignment was checked in both native libraries.

These initialization cases cover ELF lookup and basic Hook behavior.
The separate object-conversion/GC regression, typed bridge cases and R8 build
are documented in [GC_REFERENCES.md](GC_REFERENCES.md).

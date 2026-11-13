# GC-safe Hook references (Top 248)

The Paji 1.3.2 Top 248 report aborts in `Thread::AddLocalRef` while converting a
raw address in `Pine.getObject`, with `Arm64Entry.handleBridge` on the stack.
The legacy bridge saves Java references as integer register values. JNI calls,
array allocation and boxing can suspend the thread before those integers are
converted back to objects. A moving GC cannot update the saved integers. Following
a forwarding pointer, or removing its class assertion, cannot restore that lifetime
guarantee.

On API 26+ the new bridge is a small in-memory DEX method with the target's primitive
types and real reference parameters. Instance receivers become an explicit first
Object parameter. The existing native method-jump trampoline preserves the argument
calling convention; ART tracks references throughout argument array creation,
boxing, callbacks and return processing. References are erased to Object so the
bridge does not need access to private/application-loader classes. HookRecord
retains its bridge Method and therefore the generated class and loader.

No external DEX library or writable code-cache directory is needed. Failure to
create a typed bridge throws during installation instead of using the unsafe
integer bridge. `handleCall` is retained by the consumer shrinker rules because
the generated DEX calls it by name. Java classes, JNI registration and both native
libraries must be updated together.

Paji's Android minimum is API 26. Older Pine users retain the existing legacy
bridge; this patch does not claim to repair its GC behavior below API 26 or make
the public raw-address `getObject` API safe across collections. The fork's existing
policy of swallowing backup-method exceptions is unchanged. Callback exceptions
set with `CallFrame.setThrowable` are verified to propagate.

## Reproduce and verify

Build the production AAR and isolated test APK using `README.md`. Supply the
unmodified consumer AAR to reproduce the failure, then the patched consumer AAR
to compare the same `gc_references` scenario:

```sh
adb -s DEVICE shell am instrument -w -e scenario gc_references \
  top.canyie.pine.regression/.RegressionInstrumentation
```

The test runs four workers with fresh receivers, reference/null parameters, long
and double arguments spanning register and stack slots, and reference returns.
A separate thread requests 100 collections while allocating memory. It asserts
reference identity, argument values, worker termination, at least 20 actual
collections and 1,000 successful calls. Callback assertion errors are explicitly
returned through CallFrame because Pine normally logs and suppresses errors thrown
directly by callbacks.

Additional fresh-process scenarios:

- `gc_original`: same stress including the original method invocation.
- `gc_inline`, `gc_jit`: stress with inline mode or bridge JIT compilation enabled.
- `typed_default`, `typed_inline`, `typed_jit`: 17 behavior cases covering all eight
  primitive types, references, private reference arrays, zero arguments, void/null
  returns, mixed register/stack and floating point arguments, constructors,
  callback exceptions, proxies, native targets, original calls and unhook.
- The five initialization scenarios in `README.md` remain applicable.

Add `--minify` to `build_test_apk.py` to build with R8 and the supplied AAR's consumer
rules. Only regression classes are kept by test rules; Pine is optimized and
renamed normally. Use a separate output directory and uninstall the previous test
APK before installation if its generated signing key differs.

## Verified 2026-11-13

Device: Samsung SM-S9370, Android 16/API 36, arm64, 4 KB pages.

The pre-patch consumer AAR (including the earlier ELF initialization fix) crashes
in the concurrent GC test: SIGSEGV in ART's object-array store, reached from
`Arm64Entry.handleBridge`. This reproduces the invalid-reference path, but not the
exact forwarding-class assertion reported by Top 248 on Redmi.

The exact patched consumer AAR passes:

| Scenario | Calls | Actual GC count |
| --- | ---: | ---: |
| gc_references | 8,546,531 | 168 |
| gc_original | 5,981,053 | 103 |
| gc_inline | 6,480,677 | 102 |
| gc_jit | 7,153,185 | 118 |
| R8 gc_references | 6,613,057 | 102 |

All 17 behavior cases pass in each of the three normal modes and in the R8 build.
All five initialization cases pass; `normal` also passes after R8. The R8 mapping
confirms `Pine.handleCall` and `Pine$HookRecord` retain their required names.

Both native ABIs and test APK ABIs build with JDK 17 / NDK 25.2.9519653. ELF LOAD
alignment remains 16 KB, and native minimum API levels remain 21 (arm64) and 19
(ARMv7). No 32-bit runtime, API 26 device, physical 16 KB-page device or matching
Redmi firmware was available. The Paji APK itself was not rebuilt or published.

Consumer AAR SHA-256:
`2ba7a8a50b5dde209cf915b67cdc182db45a8b1dc7925ce6b9a22e52abf75aad`.
Native Build IDs: arm64 `f1310a34b977722646460cdd6c41eef696c88418`;
ARMv7 `1fc7e80ccd786671de0a6d2538eb5469d4531e38`.

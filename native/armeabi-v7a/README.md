# 32-bit (armeabi-v7a) adb

`libadb.so` is the `adb` client of Termux's `android-tools` 37.0.0-2 for `arm` (https://packages.termux.dev), and `adb32libs.zip` holds the
56 shared libraries it needs (protobuf 35.1, abseil 20260526.0, libc++ 30, brotli 1.2.0, fmt 11.2.0, lz4 1.10.0, zstd 1.5.7, zlib 1.3.2, from the
same repository), stored under the names adb asks for. They are only used by the 32-bit and universal APKs; the 64-bit APK carries the
statically linked arm64 build in `assets/libadb.so`.

`build.sh` puts `libadb.so` into `lib/armeabi-v7a/` and the zip into the assets; at start-up `AdbRuntime` unpacks the libraries into the app's
private folder and starts adb with `LD_LIBRARY_PATH` set to it.

| file | sha256 |
|---|---|
| libadb.so | 36b3f0657280a015fc0cf3ee39af823442cfee902578f3866da0ac28aa16170f |
| adb32libs.zip | 80a3aa99ea87904dc1ce3dfb88ce8ce0825ee246f1426f588ffc4958e6541b2b |

Licences: android-tools Apache-2.0, protobuf BSD-3, abseil Apache-2.0, libc++ Apache-2.0 with LLVM exception, brotli and fmt MIT, lz4 and zstd BSD, zlib zlib.

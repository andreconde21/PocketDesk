Vendored, unmodified, so the build doesn't depend on JitPack:
- libadb-android 3.1.1 (c849886) https://github.com/MuntashirAkon/libadb-android — used under Apache-2.0
- spake2-java 2.2.1, Android JNI wrapper only (the pure-Java module is incompatible with Android 16's adbd and logs its private key) (7615ddd) https://github.com/MuntashirAkon/spake2-java — LGPL-3.0
- spake2-c 0d15933 https://github.com/MuntashirAkon/spake2-c (SPAKE2 extracted from BoringSSL) — LGPL-3.0, in src/main/cpp/spake2-c

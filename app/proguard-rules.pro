# WebRTC's Java bindings are called from native code via JNI; retain their names and methods.
-keep class org.webrtc.** { *; }

# libsignal's Rust bridge discovers generated JNI declarations and @CalledFromNative entry points.
-keep class org.signal.libsignal.internal.** { *; }
-keep @org.signal.libsignal.internal.CalledFromNative class * { *; }
-keepclassmembers class * {
    @org.signal.libsignal.internal.CalledFromNative *;
    native <methods>;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# WebRTC's Java bindings are called from native code via JNI; retain their names and methods.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

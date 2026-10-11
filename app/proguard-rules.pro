# Keep JNI-bound Signal Protocol classes and annotations intact.
-keep class org.signal.libsignal.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

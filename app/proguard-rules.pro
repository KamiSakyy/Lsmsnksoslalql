# ---------------------------------------------------------------------------
# Tsuyu release shrinking rules.
# Goal: make the APK as small as possible WITHOUT touching behaviour, the
# Signal Protocol encryption stack, or anything the UI loads by name.
# ---------------------------------------------------------------------------

# Signal Protocol: JNI-bound classes, serialized session state and the
# generated protobuf types are all resolved through names or reflection.
-keep class org.signal.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Firebase Realtime Database maps plain objects through reflection, so field
# and accessor names of the model classes must survive shrinking.
-keep class com.tsuyu.messenger.Profile { *; }
-keep class com.tsuyu.messenger.data.ChatMessage { *; }
-keep class com.tsuyu.messenger.data.ChatSummary { *; }

# Views inflated from XML by their class name (constructors are looked up
# reflectively); layouts reference e.g. ZoomImageView directly.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Manifest-declared components and everything they can be started by name.
-keep class com.tsuyu.messenger.TsuyuApplication { *; }
-keep class com.tsuyu.messenger.MainActivity { *; }
-keep class com.tsuyu.messenger.notifications.** { *; }

# Generic signatures are required for Firebase bean mapping.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Optional compile-time-only dependencies pulled in by libraries.
-dontwarn org.signal.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.joda.**
-dontwarn org.slf4j.**
-dontwarn edu.umd.**
-dontwarn **

# Stream WebRTC для Android (io.getstream:stream-webrtc-android): тот же org.webrtc API.
# Нативный слой ищет классы по именам, поэтому JNI-мосты не переименовываем.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-dontwarn io.getstream.**
-dontwarn org.jetbrains.annotations.**

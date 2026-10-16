#include <jni.h>
#include <string.h>

static const char KEY[] = "ENGINE_AES_KEY_PLACEHOLDER_32B!";

JNIEXPORT jstring JNICALL
Java_com_engine_app_NativeBridge_getKey(JNIEnv* env, jclass clazz) {
    return (*env)->NewStringUTF(env, KEY);
}

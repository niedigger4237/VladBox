#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "warp_dpi_engine.h"

static void *start_from_java(JNIEnv *env, jobjectArray arguments)
{
    jsize size = (*env)->GetArrayLength(env, arguments);
    if (size > 16)
        return NULL;
    char *argv[19] = {"warp_dpi", "-U"};
    int copied = 0;
    for (; copied < size; copied++) {
        jstring arg = (*env)->GetObjectArrayElement(env, arguments, copied);
        if (!arg)
            break;
        const char *bytes = (*env)->GetStringUTFChars(env, arg, NULL);
        if (bytes) {
            argv[copied + 2] = strdup(bytes);
            (*env)->ReleaseStringUTFChars(env, arg, bytes);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[copied + 2])
            break;
    }
    void *engine = copied == size ? warp_dpi_start_args(size + 2, argv) : NULL;
    // Only numeric value options and fixed flags are allowed. The parser
    // stores their values, with no references to these strings.
    for (int i = 0; i < copied; i++)
        free(argv[i + 2]);
    return engine;
}

JNIEXPORT jlong JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeStart(
    JNIEnv *env, jclass clazz, jobjectArray arguments)
{
    (void)clazz;
    return (jlong)(intptr_t)start_from_java(env, arguments);
}

JNIEXPORT jint JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeOpenWarpStream(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    if (!handle) return -1;
    return warp_dpi_open_stream((void *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeStop(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    if (handle) warp_dpi_stop((void *)(intptr_t)handle);
}

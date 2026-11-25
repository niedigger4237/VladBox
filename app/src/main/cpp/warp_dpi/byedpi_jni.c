#include <jni.h>
#include <stdint.h>
#include <unistd.h>

/* Engine adapter contract. Implementations must own all worker threads,
 * restrict streams to the WARP API and join workers before destruction.
 * Until the engine is linked, fail closed rather than use a direct socket.
 */
#ifdef WARP_DPI_ENGINE_READY
#include "warp_dpi_engine.h"
#endif

JNIEXPORT jlong JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeStart(
    JNIEnv *env, jclass clazz, jobjectArray arguments)
{
    (void)clazz;
#ifdef WARP_DPI_ENGINE_READY
    return (jlong)(intptr_t)warp_dpi_start(env, arguments);
#else
    (void)env;
    (void)arguments;
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeOpenWarpStream(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
#ifdef WARP_DPI_ENGINE_READY
    if (!handle) return -1;
    return warp_dpi_open_stream((void *)(intptr_t)handle);
#else
    (void)handle;
    return -1;
#endif
}

JNIEXPORT void JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeStop(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
#ifdef WARP_DPI_ENGINE_READY
    if (handle) warp_dpi_stop((void *)(intptr_t)handle);
#else
    (void)handle;
#endif
}

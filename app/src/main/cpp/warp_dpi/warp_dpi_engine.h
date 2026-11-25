#ifndef WARP_DPI_ENGINE_H
#define WARP_DPI_ENGINE_H

#include <jni.h>

/*
 * Experimental engine interface.
 * Only one live engine may own upstream global parameters.
 * Start must copy Java arguments before returning and unwind on failure.
 * Open must return an owned connected descriptor restricted to WARP API,
 * never a listening socket or a direct fallback.
 * Stop must wake the event loop, close engine-owned descriptors, join
 * workers and release upstream parameters before allowing another start.
 * No process-wide signal handlers may be installed.
 */
void *warp_dpi_start(JNIEnv *env, jobjectArray arguments);
int warp_dpi_open_stream(void *engine);
void warp_dpi_stop(void *engine);

#endif

@file:Suppress("EXPERIMENTAL_API_USAGE")

package io.nekohasekai.sagernet.ktx

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

// Explicit process-lifetime owner for legacy fire-and-forget entry points.
// Service/session work should instead use its own cancellable scope.
internal val processCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun block(block: suspend CoroutineScope.() -> Unit): suspend CoroutineScope.() -> Unit {
    return block
}

fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit) =
    processCoroutineScope.launch(Dispatchers.Default, block = block)

fun Fragment.runOnLifecycleDispatcher(block: suspend CoroutineScope.() -> Unit) =
    lifecycleScope.launch(Dispatchers.Default, block = block)

suspend fun <T> onDefaultDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.Default, block = block)

fun runOnIoDispatcher(block: suspend CoroutineScope.() -> Unit) =
    processCoroutineScope.launch(Dispatchers.IO, block = block)

suspend fun <T> onIoDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.IO, block = block)

fun runOnMainDispatcher(block: suspend CoroutineScope.() -> Unit) =
    processCoroutineScope.launch(Dispatchers.Main.immediate, block = block)

suspend fun <T> onMainDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.Main.immediate, block = block)

fun runBlockingOnMainDispatcher(block: suspend CoroutineScope.() -> Unit) {
    runBlocking {
        processCoroutineScope.launch(Dispatchers.Main.immediate, block = block)
    }
}

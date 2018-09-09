package com.ymusicapp.coroutines.lifecycle.internal

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.os.Looper
import com.ymusicapp.coroutines.lifecycle.BuildConfig
import com.ymusicapp.coroutines.lifecycle.LifecycleEventChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.selects.whileSelect
import timber.log.Timber

internal inline fun <T> catchAll(printLog: Boolean = BuildConfig.DEBUG, action: () -> T): T? {
    try {
        return action()
    } catch (ignore: Throwable) {
        if (printLog) Timber.e(ignore)
    }
    return null
}

internal suspend inline fun <T> SendChannel<T>.trySend(
        value: T
): Throwable? {
    try {
        send(value)
    } catch (error: Throwable) {
        return error
    }
    return null
}

internal fun <T> SendChannel<T>.tryOffer(
        value: T,
        printLog: Boolean = BuildConfig.DEBUG
): Boolean {
    return !isClosedForSend && catchAll(printLog) { offer(value) } == true
}

internal inline val isMainThread: Boolean
    get() = Looper.myLooper() == Looper.getMainLooper()

internal val Lifecycle.isAtLeastStarted
    get() = currentState.isAtLeast(Lifecycle.State.STARTED)

internal val Lifecycle.isDestroyed
    get() = currentState == Lifecycle.State.DESTROYED

/**
 * Loop over all elements of this channel, auto pause when [lifecycleOwner] inactive, finish
 * either when [lifecycleOwner] is destroyed or all elements are consumed.
 *
 * **Note**: This function doesn't cancel source channel.
 */
internal suspend fun <T : Any> ReceiveChannel<T>.forEachWithLifecycle(
        lifecycleOwner: LifecycleOwner,
        action: suspend (T) -> Unit
) {
    val lifecycle = lifecycleOwner.lifecycle
    (this as? StatefulSubscription)?.setActive(lifecycle.isAtLeastStarted)
    if (lifecycle.isDestroyed) {
        return
    }

    val lifecycleEventChannel = LifecycleEventChannel(lifecycleOwner)
    try {
        var pendingDispatchElement: T? = null
        whileSelect {
            onReceiveOrNull { element ->
                if (element == null) return@onReceiveOrNull false
                if (lifecycle.isAtLeastStarted) {
                    action(element)
                    pendingDispatchElement = null
                } else {
                    pendingDispatchElement = element
                }
                return@onReceiveOrNull true
            }
            lifecycleEventChannel.onReceiveOrNull { _ ->
                val isActive = lifecycle.isAtLeastStarted
                (this as? StatefulSubscription)?.setActive(isActive)
                pendingDispatchElement?.let { element ->
                    if (isActive) {
                        action(element)
                        pendingDispatchElement = null
                    }
                }
                return@onReceiveOrNull true
            }
        }
    } finally {
        lifecycleEventChannel.cancel()
    }
}
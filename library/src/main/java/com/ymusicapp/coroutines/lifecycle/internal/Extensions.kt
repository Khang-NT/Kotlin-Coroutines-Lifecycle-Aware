package com.ymusicapp.coroutines.lifecycle.internal

import androidx.lifecycle.Lifecycle
import android.os.Looper
import com.ymusicapp.coroutines.lifecycle.BuildConfig
import kotlinx.coroutines.channels.SendChannel
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

internal inline val Lifecycle.isAtLeastStarted
    get() = currentState.isAtLeast(Lifecycle.State.STARTED)

internal inline val Lifecycle.isDestroyed
    get() = currentState == Lifecycle.State.DESTROYED

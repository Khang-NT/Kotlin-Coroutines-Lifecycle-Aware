package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.OnLifecycleEvent
import android.support.annotation.AnyThread
import android.support.annotation.MainThread
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ClosedSendChannelException
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.launch

/**
 * Bring [Lifecycle.Event]s to coroutine world with [Channel].
 */
@Suppress("unused")
class LifecycleEventChannel @MainThread private constructor(
        private val lifecycle: Lifecycle,
        private val channel: Channel<Lifecycle.Event>
) : LifecycleObserver, ReceiveChannel<Lifecycle.Event> by channel {

    @MainThread
    constructor(
            lifecycleOwner: LifecycleOwner,
            capacity: Int = 0
    ) : this(lifecycleOwner.lifecycle, Channel(capacity))

    @MainThread
    constructor(
            lifecycle: Lifecycle,
            capacity: Int = 0
    ) : this(lifecycle, Channel(capacity))

    init {
        check(isMainThread())
        lifecycle.addObserver(this)
    }

    @AnyThread
    override fun cancel(cause: Throwable?): Boolean {
        launchOnMainThread {
            lifecycle.removeObserver(this@LifecycleEventChannel)
        }
        return channel.cancel(cause)
    }

    @MainThread
    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun onAny(source: LifecycleOwner, event: Lifecycle.Event) {
        check(event != Lifecycle.Event.ON_ANY) { "Invalid event" }
        launch(Unconfined) {
            try {
                channel.send(event)
                if (event == Lifecycle.Event.ON_DESTROY) {
                    channel.close()
                }
            } catch (ignore: ClosedSendChannelException) {
                // channel just closed, ignore this
            }
        }
        if (event == Lifecycle.Event.ON_DESTROY) {
            lifecycle.removeObserver(this)
        }
    }
}
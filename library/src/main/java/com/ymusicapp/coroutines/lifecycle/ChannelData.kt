package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.produce
import kotlin.coroutines.experimental.CoroutineContext

fun <T> ReceiveChannel<T>.withLifecycle(
        lifecycle: Lifecycle,
        context: CoroutineContext = Unconfined,
        capacity: Int = Channel.CONFLATED
): ReceiveChannel<T> = produce(context, capacity) {
    lifecycle.currentState != Lifecycle.State.DESTROYED || return@produce

    val lifecycleEventChannel = LifecycleEventChannel(lifecycle, Channel.CONFLATED)
    val sourceData = this@withLifecycle
    val combined = combineLatest(sourceData, lifecycleEventChannel, capacity = 0)

    var latestDispatched: T? = null
    combined.consumeEach { (value, event) ->
        if (value !== latestDispatched
                && (event == Lifecycle.Event.ON_CREATE
                        || event == Lifecycle.Event.ON_RESUME
                        || event == Lifecycle.Event.ON_START)
        ) {
            latestDispatched = value
            send(value)
        }
    }
}

fun <T> ReceiveChannel<T>.withLifecycle(
        lifecycleOwner: LifecycleOwner,
        context: CoroutineContext = UI,
        capacity: Int = Channel.UNLIMITED
): ReceiveChannel<T> = withLifecycle(lifecycleOwner.lifecycle, context, capacity)
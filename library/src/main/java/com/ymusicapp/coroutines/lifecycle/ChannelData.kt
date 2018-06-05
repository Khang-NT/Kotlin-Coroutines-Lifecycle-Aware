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

/**
 * Convert a channel to "lifecycle-aware channel".
 * New channel's behavior:
 *   - It won't send new value when [lifecycle] in inactive state (e.g Fragment/Activity paused).
 *   - Latest value will deliver when [lifecycle] back to active state. (e.g Fragment/Activity resumed).
 *   - Auto call [Channel.cancel] when [lifecycle] change to [Lifecycle.State.DESTROYED] state.
 *
 * _Cancel this channel won't cancel original channel._
 *
 * @param lifecycle Lifecycle used to make new channel.
 */
fun <T> ReceiveChannel<T>.withLifecycle(
        lifecycle: Lifecycle,
        context: CoroutineContext = Unconfined,
        capacity: Int = Channel.CONFLATED
): ReceiveChannel<T> = produce(context, capacity) {
    lifecycle.currentState != Lifecycle.State.DESTROYED || return@produce

    val lifecycleEventChannel = withMainThread { LifecycleEventChannel(lifecycle, Channel.CONFLATED) }
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

/**
 * @see withLifecycle
 * @param lifecycleOwner Lifecycle used to make new channel.
 */
fun <T> ReceiveChannel<T>.withLifecycle(
        lifecycleOwner: LifecycleOwner,
        context: CoroutineContext = Unconfined,
        capacity: Int = Channel.UNLIMITED
): ReceiveChannel<T> = withLifecycle(lifecycleOwner.lifecycle, context, capacity)
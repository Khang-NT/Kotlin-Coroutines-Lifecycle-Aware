package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.launch
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Convert a channel to "lifecycle-aware channel".
 * New channel's behavior:
 *   - It won't send new value when [lifecycleOwner] in inactive state (e.g Fragment/Activity paused).
 *   - Latest value will deliver when [lifecycleOwner] back to active state. (e.g Fragment/Activity resumed).
 *   - Auto call [Channel.cancel] when [lifecycleOwner] change to [Lifecycle.State.DESTROYED] state.
 *
 * _Cancel this channel won't cancel original channel._
 *
 * @param lifecycleOwner Target's lifecycle used to make new channel.
 */
fun <T : Any> ReceiveChannel<T>.withLifecycle(
        lifecycleOwner: LifecycleOwner,
        context: CoroutineContext = Unconfined,
        capacity: Int = Channel.CONFLATED
): ReceiveChannel<T> = produce(context, capacity) {
    consumeWithLifecycle(lifecycleOwner) {
        send(it)
    }
}

fun <T : Any> ReceiveChannel<T>.withLifecycle(
        lifecycleOwner: LifecycleOwner,
        context: CoroutineContext = UI,
        parent: Job? = null,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        action: suspend (T) -> Unit
): Job = launch(context, start, parent) {
    consumeWithLifecycle(lifecycleOwner) {
        action(it)
    }
}

private suspend fun <T : Any> ReceiveChannel<T>.consumeWithLifecycle(
        lifecycleOwner: LifecycleOwner,
        action: suspend (T) -> Unit
) {
    val lifecycle = lifecycleOwner.lifecycle
    lifecycle.currentState != Lifecycle.State.DESTROYED || return

    val lifecycleEventChannel = withMainThread { LifecycleEventChannel(lifecycle, Channel.CONFLATED) }
    val sourceChannel = this@consumeWithLifecycle
    val combined = combineLatest(sourceChannel, lifecycleEventChannel, capacity = 0)
    val isStatefulSubscription = sourceChannel is StatefulSubscription

    var latestDispatched: T? = null
    combined.consumeEach { (value, _) ->
        val active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (value !== latestDispatched && active) {
            latestDispatched = value
            action(value)
        }
        if (isStatefulSubscription) {
            (sourceChannel as StatefulSubscription).setActive(active)
        }
    }
}
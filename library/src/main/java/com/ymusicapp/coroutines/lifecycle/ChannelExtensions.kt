package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.LifecycleOwner
import com.ymusicapp.coroutines.lifecycle.internal.forEachWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Unconfined
import kotlinx.coroutines.android.UI
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumes
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

fun <T : Any> ReceiveChannel<T>.withLifecycle(
        lifecycleOwner: LifecycleOwner,
        context: CoroutineContext = Unconfined,
        capacity: Int = Channel.CONFLATED
): ReceiveChannel<T> = produce(context, capacity, onCompletion = consumes()) {
    forEachWithLifecycle(lifecycleOwner) { element ->
        send(element)
    }
}

fun <T : Any> ReceiveChannel<T>.withLifecycle(
        lifecycleOwner: LifecycleOwner,
        context: CoroutineContext = UI,
        parent: Job? = null,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        action: suspend (T) -> Unit
): Job = launch(context, start, parent, onCompletion = consumes()) {
    forEachWithLifecycle(lifecycleOwner) {
        action(it)
    }
}


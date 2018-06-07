package com.ymusicapp.coroutines.lifecycle

import android.os.Looper
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.whileSelect
import kotlinx.coroutines.experimental.withContext
import kotlin.coroutines.experimental.CoroutineContext

inline fun <A, B> bothNotNull(a: A?, b: B?, action: (A, B) -> Unit) {
    if (a != null && b != null) {
        action(a, b)
    }
}

fun <A : Any, B : Any> combineLatest(
        channelA: ReceiveChannel<A>,
        channelB: ReceiveChannel<B>,
        context: CoroutineContext = Unconfined,
        capacity: Int = Channel.UNLIMITED
): ReceiveChannel<Pair<A, B>> {
    var latestA: A? = null
    var latestB: B? = null
    return produce(context, capacity) {
        whileSelect {
            channelA.onReceiveOrNull {
                it != null || return@onReceiveOrNull false
                latestA = it
                bothNotNull(latestA, latestB) { a, b ->
                    send(Pair(a, b))
                }
                return@onReceiveOrNull true
            }
            channelB.onReceiveOrNull {
                it != null || return@onReceiveOrNull false
                latestB = it
                bothNotNull(latestA, latestB) { a, b ->
                    send(Pair(a, b))
                }
                return@onReceiveOrNull true
            }
        }
    }
}

internal fun isMainThread() = Looper.myLooper() === Looper.myLooper()

internal suspend fun <T> withMainThread(action: suspend () -> T): T {
    return withContext(UI, if (isMainThread()) CoroutineStart.UNDISPATCHED else CoroutineStart.ATOMIC) {
        action()
    }
}

internal fun launchOnMainThread(action: () -> Unit) {
    launch(UI, if (isMainThread()) CoroutineStart.UNDISPATCHED else CoroutineStart.ATOMIC) {
        action()
    }
}
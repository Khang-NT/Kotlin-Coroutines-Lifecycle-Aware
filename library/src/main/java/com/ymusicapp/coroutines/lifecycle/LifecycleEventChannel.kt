package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.OnLifecycleEvent
import android.support.annotation.MainThread
import com.ymusicapp.coroutines.lifecycle.internal.isDestroyed
import com.ymusicapp.coroutines.lifecycle.internal.isMainThread
import com.ymusicapp.coroutines.lifecycle.internal.tryOffer
import com.ymusicapp.coroutines.lifecycle.internal.trySend
import kotlinx.coroutines.Unconfined
import kotlinx.coroutines.android.UI
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference

class LifecycleEventChannel private constructor(
        private val lifecycleOwnerWeakRef: WeakReference<LifecycleOwner>,
        private val channel: Channel<Lifecycle.Event>
) : LifecycleObserver, ReceiveChannel<Lifecycle.Event> by channel {

    constructor(
            lifecycleOwner: LifecycleOwner,
            capacity: Int = Channel.CONFLATED
    ) : this(WeakReference(lifecycleOwner), Channel(capacity))

    init {
        launch(UI.immediate) {
            lifecycleOwnerWeakRef.get()?.let { lifecycleOwner ->
                if (!lifecycleOwner.lifecycle.isDestroyed) {
                    lifecycleOwner.lifecycle.addObserver(this@LifecycleEventChannel)
                    return@launch
                }
            }
            // lifecycle owner dead
            channel.tryOffer(Lifecycle.Event.ON_DESTROY)
            channel.close()
        }
        channel.invokeOnClose { _ ->
            if (!isMainThread) {
                Timber.w("Detect call LifecycleEventBroadcast.close() on background thread")
            }
            launch(UI.immediate) {
                lifecycleOwnerWeakRef.get()?.lifecycle?.removeObserver(this@LifecycleEventChannel)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    @MainThread
    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun onAny(source: LifecycleOwner, event: Lifecycle.Event) {
        launch(Unconfined) {
            channel.trySend(event)?.let { error ->
                Timber.e(error)
            }
        }
        if (source.lifecycle.isDestroyed) {
            channel.close()
        }
    }
}
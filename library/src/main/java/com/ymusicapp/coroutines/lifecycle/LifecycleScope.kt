package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import com.ymusicapp.coroutines.lifecycle.internal.StatefulSubscription
import com.ymusicapp.coroutines.lifecycle.internal.isMainThread
import kotlinx.coroutines.*
import kotlinx.coroutines.android.Main
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.whileSelect
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext


class LifecycleScope<T : LifecycleOwner>(
        lifecycleOwner: T,
        parent: Job? = null
) : CoroutineScope, DefaultFullLifecycleObserver {

    private val lifecycleOwnerWeakRef = WeakReference(lifecycleOwner)
    private val lifecycleBroadcast = ConflatedBroadcastChannel<Lifecycle.State>()
    private var currentState: Lifecycle.State
    private val isAtLeastStarted: Boolean
        get() = currentState.isAtLeast(Lifecycle.State.STARTED)

    val job = Job(parent)
    override val coroutineContext: CoroutineContext = Dispatchers.Main + job

    init {
        check(isMainThread) { "Must construct LifecycleScope on Main thread" }
        currentState = lifecycleOwner.lifecycle.currentState
        if (currentState == Lifecycle.State.DESTROYED) {
            job.cancel()
            lifecycleBroadcast.close()
        } else {
            lifecycleBroadcast.offer(currentState)
            lifecycleOwner.lifecycle.addObserver(this)
        }
    }

    fun getLifecycleOwner(): T? = lifecycleOwnerWeakRef.get()

    fun getLifecycleState(): ReceiveChannel<Lifecycle.State> = lifecycleBroadcast.openSubscription()

    suspend fun <T : Any> ReceiveChannel<T>.observe(action: suspend (T) -> Unit) {
        consume {
            forEach(action)
        }
    }

    fun <T : Any> ReceiveChannel<T>.observeAsync(action: suspend (T) -> Unit): Job {
        return launch(Dispatchers.Unconfined) { observe(action) }
    }

    suspend fun <T : Any> ReceiveChannel<T>.forEach(action: suspend (T) -> Unit) {
        val lifecycleStateChannel = getLifecycleState()
        val statefulSubscription = this as? StatefulSubscription
        statefulSubscription?.setActive(isAtLeastStarted)
        lifecycleStateChannel.consume {
            var pendingDispatchValue: T? = null

            suspend fun dispatchValue() {
                val value = checkNotNull(pendingDispatchValue)
                pendingDispatchValue = null
                withContext(Dispatchers.Main.immediate) {
                    action(value)
                }
            }

            whileSelect {
                this@forEach.onReceiveOrNull { value ->
                    pendingDispatchValue = value
                    if (isAtLeastStarted && pendingDispatchValue != null) {
                        dispatchValue()
                    }
                    return@onReceiveOrNull value != null
                }
                lifecycleStateChannel.onReceiveOrNull { state ->
                    statefulSubscription?.setActive(isAtLeastStarted)
                    if (isAtLeastStarted && pendingDispatchValue != null) {
                        dispatchValue()
                    }
                    return@onReceiveOrNull state != null && state != Lifecycle.State.DESTROYED
                }
            }
        }
    }

    fun <T : Any> ReceiveChannel<T>.withLifecycle(
            context: CoroutineContext = Dispatchers.Unconfined,
            capacity: Int = Channel.CONFLATED
    ): ReceiveChannel<T> = produce(context, capacity, onCompletion = consumes()) {
        forEach(this::send)
    }


    override fun onAny(owner: LifecycleOwner, event: Lifecycle.Event) {
        super.onAny(owner, event)
        if (currentState != owner.lifecycle.currentState) {
            currentState = owner.lifecycle.currentState
            lifecycleBroadcast.offer(currentState)
        }
        if (currentState == Lifecycle.State.DESTROYED) {
            job.cancel()
            lifecycleBroadcast.close()
            owner.lifecycle.removeObserver(this)
        }
    }

}

private interface DefaultFullLifecycleObserver : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) = onAny(owner, Lifecycle.Event.ON_CREATE)

    override fun onResume(owner: LifecycleOwner) = onAny(owner, Lifecycle.Event.ON_RESUME)

    override fun onPause(owner: LifecycleOwner) = onAny(owner, Lifecycle.Event.ON_PAUSE)

    override fun onStart(owner: LifecycleOwner) = onAny(owner, Lifecycle.Event.ON_START)

    override fun onStop(owner: LifecycleOwner) = onAny(owner, Lifecycle.Event.ON_STOP)

    override fun onDestroy(owner: LifecycleOwner) = onAny(owner, Lifecycle.Event.ON_DESTROY)

    fun onAny(owner: LifecycleOwner, event: Lifecycle.Event) = Unit
}


fun <T : LifecycleOwner> T.lifecycleScope(parent: Job? = null): LifecycleScope<T> {
    return LifecycleScope(this, parent)
}
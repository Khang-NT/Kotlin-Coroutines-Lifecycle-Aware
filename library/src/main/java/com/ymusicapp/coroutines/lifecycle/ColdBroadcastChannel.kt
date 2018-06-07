package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.OnLifecycleEvent
import android.support.annotation.AnyThread
import android.support.annotation.MainThread
import kotlinx.coroutines.experimental.channels.BroadcastChannel
import kotlinx.coroutines.experimental.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SubscriptionReceiveChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * This class uses [ConflatedBroadcastChannel] implementation as backend, with addition tracking
 * subscription feature.
 * Subclasses can override [onBecomeActive] and [onBecomeInactive] to start/stop working its job.
 */
@Suppress("unused")
open class ColdBroadcastChannel<T : Any>(
        private val conflatedBroadcastChannel: ConflatedBroadcastChannel<T>
) : BroadcastChannel<T> by conflatedBroadcastChannel {

    constructor() : this(ConflatedBroadcastChannel())

    private val isActive = AtomicBoolean(false)
    private val activeSubscriptionCount = AtomicInteger(0)

    /**
     * Called when this channel changed from inactive to active ([hasActiveSubscriptions] = true)
     */
    @AnyThread
    protected open fun onBecomeActive() = Unit

    /**
     * Called when this channel changed from active to inactive ([hasActiveSubscriptions] = false)
     *
     * _Note: [ColdBroadcastChannel] startup state is inactive._
     */
    @AnyThread
    protected open fun onBecomeInactive() = Unit

    @AnyThread
    @Synchronized
    private fun onActiveChanged(active: Boolean) {
        if (active) {
            onBecomeActive()
        } else {
            onBecomeInactive()
        }
    }

    /**
     * @see ConflatedBroadcastChannel.value
     */
    val value: T get() = conflatedBroadcastChannel.value

    /**
     * @see ConflatedBroadcastChannel.valueOrNull
     */
    val valueOrNull: T? get() = conflatedBroadcastChannel.valueOrNull

    /**
     * Check if this broadcast channel has any **active** subscription.
     * @return true if there are at least open subscription in active state.
     */
    fun hasActiveSubscriptions() = isActive.get()

    override fun openSubscription(): SubscriptionReceiveChannel<T> {
        return SubscriptionReceiveChannelWrapper(conflatedBroadcastChannel.openSubscription())
                .also { onActivate(it) }
    }

    @MainThread
    fun openSubscription(lifecycleOwner: LifecycleOwner): SubscriptionReceiveChannel<T> {
        check(isMainThread())
        val lifecycle = lifecycleOwner.lifecycle
        val subscription = conflatedBroadcastChannel.openSubscription()
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            subscription.cancel()
            return subscription
        }
        return SubscriptionReceiveChannelWithLifecycle(lifecycle, subscription)
                .also { onActivate(it) }
    }

    @AnyThread
    private fun onDeactivate(subscriptionWrapper: SubscriptionReceiveChannelWrapper<*>) {
        if (subscriptionWrapper.activating.compareAndSet(true, false)) {
            val activeSubscriptionCount = activeSubscriptionCount.decrementAndGet()
            check(activeSubscriptionCount >= 0)
            if (activeSubscriptionCount == 0 && isActive.compareAndSet(true, false)) {
                onActiveChanged(false)
            }
        }
    }

    @AnyThread
    private fun onActivate(subscriptionWrapper: SubscriptionReceiveChannelWrapper<*>) {
        if (subscriptionWrapper.activating.compareAndSet(false, true)) {
            activeSubscriptionCount.incrementAndGet()
            if (isActive.compareAndSet(false, true)) {
                onActiveChanged(true)
            }
        }
    }

    private open inner class SubscriptionReceiveChannelWrapper<T : Any>(
            private val subscription: ReceiveChannel<T>
    ) : SubscriptionReceiveChannel<T>, ReceiveChannel<T> by subscription {

        val activating: AtomicBoolean = AtomicBoolean(false)

        override fun cancel(cause: Throwable?): Boolean {
            onDeactivate(this)
            return subscription.cancel(cause)
        }
    }

    private inner class SubscriptionReceiveChannelWithLifecycle<T : Any> @MainThread constructor(
            private val lifecycle: Lifecycle,
            subscription: ReceiveChannel<T>
    ) : SubscriptionReceiveChannelWrapper<T>(subscription.withLifecycle(lifecycle)), LifecycleObserver {

        private val observingLifecycle = AtomicBoolean(true)

        init {
            check(isMainThread())
            lifecycle.addObserver(this)
        }

        @MainThread
        @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
        fun onAny(source: LifecycleOwner, event: Lifecycle.Event) {
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                cancel()
                return
            } else {
                val shouldActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (shouldActive) {
                    onActivate(this)
                } else {
                    onDeactivate(this)
                }
            }
        }

        override fun cancel(cause: Throwable?): Boolean {
            if (observingLifecycle.compareAndSet(true, false)) {
                launchOnMainThread {
                    lifecycle.removeObserver(this)
                }
            }
            return super.cancel(cause)
        }

    }
}
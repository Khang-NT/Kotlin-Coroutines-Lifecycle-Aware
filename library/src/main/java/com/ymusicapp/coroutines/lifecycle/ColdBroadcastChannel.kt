package com.ymusicapp.coroutines.lifecycle

import android.arch.lifecycle.LifecycleOwner
import android.support.annotation.AnyThread
import com.ymusicapp.coroutines.lifecycle.internal.StatefulSubscription
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * This class uses [ConflatedBroadcastChannel] implementation as backend, with addition tracking
 * subscription feature.
 * Subclasses can override [onBecomeActive] and [onBecomeInactive] to start/stop working its job.
 */
@Suppress("unused")
open class ColdBroadcastChannel<T : Any> private constructor(
        private val conflatedBroadcastChannel: ConflatedBroadcastChannel<T>
) : BroadcastChannel<T> by conflatedBroadcastChannel {

    constructor() : this(ConflatedBroadcastChannel())

    constructor(initValue: T) : this(ConflatedBroadcastChannel(initValue))

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


    override fun openSubscription(): ReceiveChannel<T> {
        return StatefulSubscriptionReceiveChannel(conflatedBroadcastChannel.openSubscription())
    }

    fun openSubscription(lifecycleOwner: LifecycleOwner?): ReceiveChannel<T> {
        return openSubscription().run {
            if (lifecycleOwner != null) withLifecycle(lifecycleOwner) else this
        }
    }

    @AnyThread
    private fun onDeactivate(subscriptionWrapper: StatefulSubscriptionReceiveChannel<*>) {
        if (subscriptionWrapper.activating.compareAndSet(true, false)) {
            val activeSubscriptionCount = activeSubscriptionCount.decrementAndGet()
            check(activeSubscriptionCount >= 0)
            if (activeSubscriptionCount == 0 && isActive.compareAndSet(true, false)) {
                onActiveChanged(false)
            }
        }
    }

    @AnyThread
    private fun onActivate(subscriptionWrapper: StatefulSubscriptionReceiveChannel<*>) {
        if (subscriptionWrapper.activating.compareAndSet(false, true)) {
            activeSubscriptionCount.incrementAndGet()
            if (isActive.compareAndSet(false, true)) {
                onActiveChanged(true)
            }
        }
    }

    private inner class StatefulSubscriptionReceiveChannel<T : Any> constructor(
            private val subscriptionChannel: ReceiveChannel<T>
    ) : ReceiveChannel<T> by subscriptionChannel, StatefulSubscription {

        val activating: AtomicBoolean = AtomicBoolean(false)

        override fun setActive(active: Boolean) {
            if (active) {
                onActivate(this)
            } else {
                onDeactivate(this)
            }
        }

        override fun cancel(cause: Throwable?): Boolean {
            return subscriptionChannel.cancel(cause).also { closed ->
                if (closed) onDeactivate(this)
            }
        }
    }

}
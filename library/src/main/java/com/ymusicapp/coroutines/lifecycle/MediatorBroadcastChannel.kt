package com.ymusicapp.coroutines.lifecycle

import com.ymusicapp.coroutines.lifecycle.internal.StatefulSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

class MediatorBroadcastChannel<T : Any> : ColdBroadcastChannel<T>() {

    private val lock = Any()
    private val sourceList = CopyOnWriteArraySet<Source<*>>()

    fun <E> addSource(
            coldBroadcastChannel: BroadcastChannel<E>,
            consumer: suspend MediatorBroadcastChannel<T>.(E) -> Unit
    ) {
        synchronized(lock) {
            val source = Source(coldBroadcastChannel, consumer)
            sourceList.add(source)
            if (hasActiveSubscriptions()) {
                source.plugIfNotYet()
            }
        }
    }

    fun addSource(coldBroadcastChannel: BroadcastChannel<T>) {
        addSource(coldBroadcastChannel) { send(it) }
    }

    fun removeSource(coldBroadcastChannel: BroadcastChannel<*>) {
        synchronized(lock) {
            sourceList.removeAll { source ->
                if (source.origin === coldBroadcastChannel) {
                    source.unplugIfNotYet()
                    source.clear()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onBecomeActive() {
        super.onBecomeActive()
        synchronized(lock) {
            sourceList.forEach { it.plugIfNotYet() }
        }
    }

    override fun onBecomeInactive() {
        super.onBecomeInactive()
        synchronized(lock) {
            sourceList.forEach { it.unplugIfNotYet() }
        }
    }

    private inner class Source<E>(
            val origin: BroadcastChannel<E>,
            val consumer: suspend MediatorBroadcastChannel<T>.(E) -> Unit
    ) {
        private val sourceLock = Any()
        private val subscription = origin.openSubscription()
        private var pluggingJob: Job? = null

        fun plugIfNotYet() {
            synchronized(sourceLock) {
                if (pluggingJob?.isActive != true) {
                    pluggingJob = GlobalScope.launch(Dispatchers.Unconfined) {
                        // do not close subscription until clear()
                        for (element in subscription) {
                            consumer(element)
                        }
                    }
                    if (subscription is StatefulSubscription) {
                        subscription.setActive(true)
                    }
                }
            }
        }

        fun unplugIfNotYet() {
            synchronized(sourceLock) {
                pluggingJob?.cancel()
                if (subscription is StatefulSubscription) {
                    subscription.setActive(false)
                }
            }
        }

        fun clear() {
            subscription.cancel()
        }

    }
}
package com.ymusicapp.coroutines.lifecycle

import android.support.annotation.GuardedBy
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.BroadcastChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch

class MediatorBroadcastChannel<T : Any> : ColdBroadcastChannel<T>() {

    private val sourceListLock = Any()
    @GuardedBy("sourceListLock")
    private val sourceList = mutableListOf<Source<*>>()

    fun <E> addSource(
            coldBroadcastChannel: BroadcastChannel<E>,
            consumer: suspend MediatorBroadcastChannel<T>.(E) -> Unit
    ) {
        synchronized(sourceListLock) {
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
        synchronized(sourceListLock) {
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
        synchronized(sourceListLock) {
            sourceList.forEach { it.plugIfNotYet() }
        }
    }

    override fun onBecomeInactive() {
        super.onBecomeInactive()
        synchronized(sourceListLock) {
            sourceList.forEach { it.unplugIfNotYet() }
        }
    }

    private inner class Source<E>(
            val origin: BroadcastChannel<E>,
            val consumer: suspend MediatorBroadcastChannel<T>.(E) -> Unit
    ) {
        private val subscription = origin.openSubscription()
        private var pluggingJob: Job? = null

        fun plugIfNotYet() {
            synchronized(this) {
                if (pluggingJob?.isActive != true) {
                    if (subscription is StatefulSubscription) {
                        subscription.setActive(true)
                    }
                    pluggingJob = launch(Unconfined) {
                        // do not close subscription until clear()
                        for (element in subscription) {
                            consumer(element)
                        }
                    }
                }
            }
        }

        fun unplugIfNotYet() {
            synchronized(this) {
                if (pluggingJob?.isActive == true) {
                    pluggingJob?.cancel()
                    if (subscription is StatefulSubscription) {
                        subscription.setActive(false)
                    }
                }
            }
        }

        fun clear() {
            subscription.close()
        }

    }
}
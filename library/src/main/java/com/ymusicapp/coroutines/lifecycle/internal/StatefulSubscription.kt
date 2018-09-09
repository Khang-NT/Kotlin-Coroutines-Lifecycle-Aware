package com.ymusicapp.coroutines.lifecycle.internal

internal interface StatefulSubscription {
    fun setActive(active: Boolean)
}
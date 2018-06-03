## Coroutine channel lifecycle-aware

If you've already tried both coroutine channel and [Jetpack's LiveData](https://developer.android.com/jetpack/), you
will find that coroutine channel is more robust and flexible than LiveData, but it lacks of handling
Android's lifecycle.

This library aims to make coroutine channel aware with Android's lifecycle, especially acting like
[LiveData's behavior](https://developer.android.com/topic/libraries/architecture/livedata).

### Lifecycle-aware
```kotlin
// with LiveData
val liveData: LiveData<Int> = TODO()
liveData.observe(lifecycleOwner, Observer { value: Int? ->
    if (value != null) {
        // consume value here
    }
})

// with channel
val receiveChannel: ReceiveChannel<Int> = TODO()
launch(UI) {
    receiveChannel.withLifecycle(lifecycleOwner).consumeEach { value: Int ->
        // value always non-null, consume it here
    }
}
```

Now you can use **`withLifecycle`** extension to convert your channel to "lifecycle-aware channel":
  * This channel won't notify new value when `lifecycleOwner` is inactive (e.g Activity/Fragment paused)
  * Latest value will deliver when `lifecycleOwner` back to `RESUMED` state.
  * Auto `cancel()` when `lifecycleOwner` goes to `DESTROYED` state, no more worry about memory leak

### How to stop receiving update

With `LiveData`, you can call `removeObserver(observer)` or `removeObservers(lifecycleOwner)`.

With coroutine channel, we have various ways to stop receive update:
  * Cancel the job of coroutine context
  * Cancel the channel returned by method `withLifecycle`
    _Note: cancel this chanel doesn't cancel the `receiveChannel`_
  * Cancel the `receiveChannel`

### Multiple observers

With `LiveData`, you can call `observe()` method multiple time, then all observers will receive the same update.

With coroutine channel, it depends on which type of channel you are using:
  * If you want each value only consume only one time, by one observer. Use `Channel()`
  * If you want all observer receive same update. Use `BroadcastChannel()`

### Observe data on background thread?

With `LiveData`, you can't observe data on background thread directly.

With coroutine channel, this is much more flexible, just consume our channel using other dispatcher in coroutine context, such as `CommonPool`.

## Install

Developing.
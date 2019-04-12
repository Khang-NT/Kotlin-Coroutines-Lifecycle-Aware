# Coroutine channel lifecycle-aware
If you've already tried both coroutine channel and [Jetpack's LiveData](https://developer.android.com/jetpack/), you
will find that coroutine channel is more robust and flexible than LiveData, but it lacks of handling
Android's lifecycle.

This library aims to make coroutine channel aware with Android's lifecycle, especially acting like
[LiveData's behavior](https://developer.android.com/topic/libraries/architecture/livedata).

# Install

Latest version in `jcenter()` is out of date, I will try to publish new one as soon as possible,
for now you can clone the source code and import to your project manually.

# Example usage

```kotlin
class YourActivity : AppCompatActivity() {

    // create CoroutineScope using lifecycleScope() extension
    // this scope has Main dispatcher in coroutine context, plus some convenience methods below
    private val lifecycleScope by lazy { this.lifecycleScope() }

    private val yourViewModel by lazy<YourViewModel> { TODO() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // with lifecycleScope, here is ALL what you can do with it

        lifecycleScope.launch {
            // ... create new coroutine (launch, async) that auto cancel when activity destroy
        }

        with(lifecycleScope) {
            launch { // just like launch statement above, but here you can use this magic extension
                yourViewModel.receiveData().observe { value ->
                    println(value)
                    // "observe" behaves like "consumeEach", but it won't callback during activity inactive
                    // + Only last item is kept during activity inactive
                    // + When activity active back, it emit the last item (if any) and continue
                }
            }

            // short form of the code above
            yourViewModel.receiveData().observeAsync { value ->
                println(value)
            }
        }
    }
}

class YourViewModel : ViewModel(), CoroutineScope {
    private val masterJob = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + masterJob

    // You can use any type of BroadcastChannel here
    // BUT ConflatedBroadcastChannel is recommended, because it replay last item when open new subscription
    // And because of another reason, regardless the behavior of broadcast channel, when
    // use method "observe" and "observeAsync", only last item is kept during activity inactive.
    private val yourBroadcastChannel = ConflatedBroadcastChannel<Int>()

    init {
        // launch your worker in background thread
        // independent with Activity/Fragment lifecycle
        launch {
            while (isActive) {
                yourBroadcastChannel.offer(Random().nextInt())
                delay(1000)
            }
        }
    }

    fun receiveData() = yourBroadcastChannel.openSubscription()

    override fun onCleared() {
        super.onCleared()
        masterJob.cancel()
    }
}

```

# App using this library

- [YMusic](https://ymusic.io)
- MediaConverterPro: [Source](https://github.com/Khang-NT/Android-Media-Converter/), [PlayStore](https://play.google.com/store/apps/details?id=com.github.khangnt.mcp)

# License
[Apache License Version 2.0](LICENSE)
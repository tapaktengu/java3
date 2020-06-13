package okhttp3.benchmark

import okhttp3.HttpUrl

interface Subject {
  fun startRequest(startNanos: Long, url: HttpUrl, listener: Listener)

  interface Listener {
    fun callSucceeded(startNanos: Long)
    fun callFailed(startNanos: Long)
  }
}

package okhttp3.benchmark

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Protocol.HTTP_1_1
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.io.InProcessNetwork
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpSubject(
  network: InProcessNetwork? = null,
  http2: Boolean,
  serverCertificate: HeldCertificate
) : Subject {
  private val okHttpClient: OkHttpClient

  init {
    val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate)
        .build()
    val protocols = when {
      http2 -> listOf(Protocol.HTTP_2, HTTP_1_1)
      else -> listOf(HTTP_1_1)
    }
    okHttpClient = OkHttpClient.Builder()
        .protocols(protocols)
        .apply {
          if (network != null) socketFactory(network.socketFactory)
        }
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .dispatcher(okhttp3.Dispatcher().apply {
          this.maxRequests = Integer.MAX_VALUE
          this.maxRequestsPerHost = Integer.MAX_VALUE
        })
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(
            maxIdleConnections = 1_000,
            keepAliveDuration = 1,
            timeUnit = TimeUnit.MINUTES
        ))
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .writeTimeout(0L, TimeUnit.MILLISECONDS)
        .connectTimeout(0L, TimeUnit.MILLISECONDS)
        .build()
  }

  override fun startRequest(startNanos: Long, url: HttpUrl, listener: Subject.Listener) {
    val call = okHttpClient.newCall(Request.Builder()
        .url(url)
        .build())
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        listener.callFailed(startNanos)
      }

      override fun onResponse(call: Call, response: Response) {
        try {
          response.use {
            response.body!!.string()
          }
          listener.callSucceeded(startNanos)
        } catch (e: Exception) {
          listener.callFailed(startNanos)
        }
      }
    })
  }
}

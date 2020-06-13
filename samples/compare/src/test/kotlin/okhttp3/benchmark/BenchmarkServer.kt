package okhttp3.benchmark

import okhttp3.HttpUrl
import okhttp3.internal.http2.Settings
import okhttp3.internal.io.InProcessNetwork
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class BenchmarkServer(
  network: InProcessNetwork? = null,
  serverCertificate: HeldCertificate,
  val maxConcurrentStreams: Int
) {
  private val mockWebServer: MockWebServer = MockWebServer()
  lateinit var url: HttpUrl

  init {
    val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(serverCertificate)
        .build()
//    mockWebServer.useHttps(serverCertificates.sslSocketFactory(), false)
    if (network != null) {
      mockWebServer.serverSocketFactory = network.serverSocketFactory
    }
    mockWebServer.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val content = "{}"
        val settings: Settings?
        if (request.sequenceNumber == 0) {
          settings = Settings()
          settings[Settings.MAX_CONCURRENT_STREAMS] = maxConcurrentStreams
        } else {
          settings = null
        }
        return MockResponse()
            .withSettings(settings)
            .setBody(content)
      }
    }
  }

  fun start(): HttpUrl {
    mockWebServer.start()
    return mockWebServer.url("/")
  }

  fun stop() {
    mockWebServer.shutdown()
  }
}

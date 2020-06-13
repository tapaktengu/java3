package okhttp3.benchmark

import okhttp3.HttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect.NORMAL
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import javax.net.SocketFactory

class Java11HttpClientSubject(
  http2: Boolean,
  serverCertificate: HeldCertificate
) : Subject {
  private val httpClient: HttpClient

  init {
    SocketFactory.getDefault()

    val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(serverCertificate.certificate)
        .build()
    val version = when {
      http2 -> HttpClient.Version.HTTP_2
      else -> HttpClient.Version.HTTP_1_1
    }
    httpClient = HttpClient.newBuilder()
        .followRedirects(NORMAL)
        .sslContext(clientCertificates.sslContext())
        .version(version)
        .build()
  }

  override fun startRequest(startNanos: Long, url: HttpUrl, listener: Subject.Listener) {
    val request = HttpRequest.newBuilder(url.toUri())
        .build()

    httpClient.sendAsync(request, BodyHandlers.ofString())
        .handle { result, exception ->
          if (exception == null) {
            val body = result.body()
            listener.callSucceeded(startNanos)
          } else {
            listener.callFailed(startNanos)
          }
        }
  }
}

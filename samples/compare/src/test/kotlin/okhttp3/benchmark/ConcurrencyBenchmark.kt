package okhttp3.benchmark

import okhttp3.internal.io.InProcessNetwork
import okhttp3.tls.HeldCertificate
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyBenchmark(
  private val concurrentTarget: Int
) : Subject.Listener {
  private val server: BenchmarkServer
  private val subject: Subject

  private val startedCount = AtomicInteger()
  private val successCount = AtomicInteger()
  private val failCount = AtomicInteger()

  init {
    val serverCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .ecdsa256()
        .build()

    val network = InProcessNetwork()

    server = BenchmarkServer(
//        network = network,
        serverCertificate = serverCertificate,
        maxConcurrentStreams = Integer.MAX_VALUE
    )
    subject = OkHttpSubject(
//        network = network,
        http2 = false,
        serverCertificate = serverCertificate
    )
//    subject = Java11HttpClientSubject(
//        http2 = false,
//        serverCertificate = serverCertificate
//    )
  }

  fun printStats() {
    var lastSuccessCount = successCount.get()
    var lastFailCount = failCount.get()
    var lastTime = System.nanoTime()

    while (true) {
      val currentSuccessCount = successCount.get()
      val currentFailCount = failCount.get()
      val currentTime = System.nanoTime()

      val elapsedSeconds = (currentTime - lastTime) / 1_000_000_000.0
      val successPerSecond = (currentSuccessCount - lastSuccessCount) / elapsedSeconds
      val failuresPerSecond = (currentFailCount - lastFailCount) / elapsedSeconds
      println(String.format("%8.2f success/sec %8.2f fail/sec", successPerSecond, failuresPerSecond))

      lastSuccessCount = currentSuccessCount
      lastFailCount = currentFailCount
      lastTime = currentTime

      Thread.sleep(250)
    }
  }

  fun runBenchmark() {
    val url = server.start()

    while (true) {
      val runningCalls = startedCount.get() - (successCount.get() + failCount.get())
      if (runningCalls < concurrentTarget) {
        val startNanos = System.nanoTime()
        startedCount.incrementAndGet()
        subject.startRequest(startNanos, url, this@ConcurrencyBenchmark)
      } else {
        Thread.sleep(1)
      }
    }
  }

  override fun callSucceeded(startNanos: Long) {
    val elapsedNanos = System.nanoTime() - startNanos
    if (elapsedNanos < 200_000_000) {
      successCount.incrementAndGet()
    } else {
      failCount.incrementAndGet()
    }
  }

  override fun callFailed(startNanos: Long) {
    failCount.incrementAndGet()
  }
}

fun main() {
  val benchmark = ConcurrencyBenchmark(64)

  val printStats = object : Thread("print stats") {
    override fun run() {
      benchmark.printStats()
    }
  }
  val runBenchmark = object : Thread("run benchmark") {
    override fun run() {
      benchmark.runBenchmark()
    }
  }
  printStats.start()
  runBenchmark.start()
}

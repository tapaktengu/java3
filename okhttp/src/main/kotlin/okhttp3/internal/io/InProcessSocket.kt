package okhttp3.internal.io

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.IOException
import okio.Pipe
import okio.Sink
import okio.buffer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ServerSocketFactory
import javax.net.SocketFactory

class InProcessNetwork(
  val bufferSize: Long = 1024 * 1024L
) {
  private val unboundLocalAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
  private val defaultLocalAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
  private val defaultLocalPort = 1

  private val acceptQueue = LinkedBlockingQueue<InProcessSocket>()

  private val serverSocket: ServerSocket = object : ServerSocket() {
    private val noMoreSockets = InProcessSocket()
    private val closed = AtomicBoolean(false)

    override fun accept(): InProcessSocket {
      val accepted = acceptQueue.take()
      if (accepted == noMoreSockets) {
        acceptQueue.add(noMoreSockets)
        throw IOException("closed")
      }
      return accepted
    }

    override fun isClosed() = closed.get()

    override fun close() {
      closed.set(true)
      acceptQueue.add(noMoreSockets)
    }
  }

  val serverSocketFactory: ServerSocketFactory = object : ServerSocketFactory() {
    override fun createServerSocket() = serverSocket
    override fun createServerSocket(port: Int) = serverSocket
    override fun createServerSocket(port: Int, backlog: Int) = serverSocket
    override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?) = serverSocket
  }

  val socketFactory: SocketFactory = object : SocketFactory() {
    override fun createSocket(): Socket {
      return InProcessSocket()
    }

    override fun createSocket(
      host: String,
      port: Int
    ): Socket {
      return InProcessSocket()
          .also { it.connect(InetSocketAddress(host, port)) }
    }

    override fun createSocket(
      host: String,
      port: Int,
      localHost: InetAddress,
      localPort: Int
    ): Socket {
      return InProcessSocket()
          .also { it.bind(InetSocketAddress(localHost, localPort)) }
          .also { it.connect(InetSocketAddress(host, port)) }
    }

    override fun createSocket(
      host: InetAddress,
      port: Int
    ): Socket {
      return InProcessSocket()
          .also { it.connect(InetSocketAddress(host, port)) }
    }

    override fun createSocket(
      address: InetAddress,
      port: Int,
      localAddress: InetAddress,
      localPort: Int
    ): Socket {
      return InProcessSocket()
          .also { it.bind(InetSocketAddress(localAddress, localPort)) }
          .also { it.connect(InetSocketAddress(address, port)) }
    }
  }

  private inner class InProcessSocket(
    /** The local socket address, or null if this is a client socket that isn't bound yet. */
    private var localInetSocketAddress: InetSocketAddress? = null,

    /** The local socket address, or null if this is a client socket that isn't connected yet. */
    private var remoteInetSocketAddress: InetSocketAddress? = null,

    /** Bytes written by this socket and read by the peer. */
    private val output: CancelablePipe = CancelablePipe(
        bufferSize
    ),

    /** Bytes written by the peer and read by this socket. */
    private val input: CancelablePipe = CancelablePipe(
        bufferSize
    )
  ) : Socket() {
    private val source: BufferedSource = input.source.buffer()
      get() {
        if (!isConnected) throw SocketException("not connected")
        return field
      }

    private val sink: BufferedSink = output.sink.buffer()
      get() {
        if (!isConnected) throw SocketException("not connected")
        return field
      }

    override fun bind(bindpoint: SocketAddress) {
      if (localInetSocketAddress != null) throw SocketException("already bound")
      if (remoteInetSocketAddress != null) throw SocketException("already connected")
      localInetSocketAddress = bindpoint as? InetSocketAddress
          ?: throw SocketException("unexpected address type")
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) {
      if (remoteInetSocketAddress != null) {
        throw SocketException("already connected")
      }

      if (localInetSocketAddress == null) {
        bind(InetSocketAddress(defaultLocalAddress, defaultLocalPort))
      }

      remoteInetSocketAddress = endpoint as? InetSocketAddress
          ?: throw SocketException("unexpected address type")

      val peer = InProcessSocket(
          remoteInetSocketAddress = localInetSocketAddress,
          localInetSocketAddress = remoteInetSocketAddress,
          output = input,
          input = output
      )

      acceptQueue.add(peer)
    }

    override fun close() {
      input.cancel()
      output.cancel()
    }

    override fun isConnected() = remoteInetSocketAddress != null && localInetSocketAddress != null

    override fun isBound() = localInetSocketAddress != null

    override fun getLocalSocketAddress() = localInetSocketAddress

    override fun getLocalAddress(): InetAddress =
      localInetSocketAddress?.address ?: unboundLocalAddress

    override fun getLocalPort() = localInetSocketAddress?.port ?: -1

    override fun getRemoteSocketAddress() = remoteInetSocketAddress

    override fun getInetAddress(): InetAddress? =
      remoteInetSocketAddress?.address

    override fun getPort() = remoteInetSocketAddress?.port ?: -1

    override fun getInputStream() = source.inputStream()

    override fun getOutputStream() = sink.outputStream()
  }

  // TODO(jwilson): add a cancel() method to okio.Pipe.
  class CancelablePipe(bufferSize: Long) {
    val pipe = Pipe(bufferSize)
    val canceled = AtomicBoolean(false)

    val sink: Sink = object : ForwardingSink(pipe.sink) {
      override fun write(source: Buffer, byteCount: Long) {
        try {
          return delegate.write(source, byteCount)
        } catch (e: IllegalStateException) {
          if (canceled.get()) throw IOException("canceled")
        }
      }

      override fun flush() {
        try {
          return delegate.flush()
        } catch (e: IllegalStateException) {
          if (canceled.get()) throw IOException("canceled")
        }
      }

      override fun close() {
        try {
          delegate.close()
        } catch (e: IllegalStateException) {
          if (canceled.get()) throw IOException("canceled")
        }
      }
    }

    val source = object : ForwardingSource(pipe.source) {
      override fun read(sink: Buffer, byteCount: Long): Long {
        try {
          return delegate.read(sink, byteCount)
        } catch (e: IllegalStateException) {
          if (canceled.get()) throw IOException("canceled")
          throw e
        }
      }

      override fun close() {
        try {
          return delegate.close()
        } catch (e: IllegalStateException) {
          if (canceled.get()) throw IOException("canceled")
        }
      }
    }

    fun cancel() {
      canceled.set(true)
      source.close()
      sink.close()
    }
  }
}

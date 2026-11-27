package com.nocturne.visualizer

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Tiny WebSocket helpers (RFC 6455) so the app needs no extra libraries: a client for talking to TVs, framing for the local server. */
object WsFrame {
    const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    fun accept(key: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray()), Base64.NO_WRAP)

    /** Writes one frame. Clients must mask; servers must not. */
    fun write(out: OutputStream, op: Int, data: ByteArray, mask: Boolean) {
        val n = data.size
        val head = ByteArrayOutputStream(14)
        head.write(0x80 or op)
        val m = if (mask) 0x80 else 0
        when {
            n < 126 -> head.write(m or n)
            n < 65536 -> { head.write(m or 126); head.write(n shr 8); head.write(n and 255) }
            else -> { head.write(m or 127); for (s in 56 downTo 0 step 8) head.write(((n.toLong() shr s) and 255).toInt()) }
        }
        if (mask) {
            val k = ByteArray(4).also { SecureRandom().nextBytes(it) }
            head.write(k)
            val d = ByteArray(n) { (data[it].toInt() xor k[it and 3].toInt()).toByte() }
            out.write(head.toByteArray()); out.write(d)
        } else { out.write(head.toByteArray()); out.write(data) }
        out.flush()
    }

    private fun readFully(inp: InputStream, b: ByteArray) {
        var o = 0
        while (o < b.size) { val r = inp.read(b, o, b.size - o); if (r < 0) throw java.io.EOFException(); o += r }
    }

    /** Reads one frame: returns (opcode, payload). */
    fun read(inp: InputStream): Pair<Int, ByteArray> {
        val h = ByteArray(2); readFully(inp, h)
        val op = h[0].toInt() and 15
        val masked = (h[1].toInt() and 0x80) != 0
        var n = (h[1].toInt() and 127).toLong()
        if (n == 126L) { val e = ByteArray(2); readFully(inp, e); n = ((e[0].toInt() and 255) shl 8 or (e[1].toInt() and 255)).toLong() }
        else if (n == 127L) { val e = ByteArray(8); readFully(inp, e); n = 0; for (x in e) n = (n shl 8) or (x.toLong() and 255) }
        if (n > 8_000_000) throw java.io.IOException("frame too big")
        val k = ByteArray(4); if (masked) readFully(inp, k)
        val d = ByteArray(n.toInt()); readFully(inp, d)
        if (masked) for (i in d.indices) d[i] = (d[i].toInt() xor k[i and 3].toInt()).toByte()
        return op to d
    }

    fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar()); if (sb.length > 8192) return sb.toString()
        }
    }
}

/** A blocking WebSocket client; TVs use self-signed certificates on the local network, so those are accepted. */
class WsClient(private val url: String) {
    private lateinit var sock: Socket
    private lateinit var inp: InputStream
    private lateinit var out: OutputStream

    fun connect(timeoutMs: Int = 4000) {
        val u = URI(url)
        val secure = u.scheme == "wss"
        val port = if (u.port > 0) u.port else if (secure) 443 else 80
        val raw = Socket(); raw.connect(InetSocketAddress(u.host, port), timeoutMs); raw.soTimeout = timeoutMs
        sock = if (secure) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
            (ctx.socketFactory.createSocket(raw, u.host, port, true) as SSLSocket).also { it.soTimeout = timeoutMs; it.startHandshake() }
        } else raw
        inp = sock.getInputStream().buffered(); out = sock.getOutputStream()
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val path = (u.rawPath.ifEmpty { "/" }) + (if (u.rawQuery != null) "?" + u.rawQuery else "")
        val req = "GET $path HTTP/1.1\r\nHost: ${u.host}:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nOrigin: http://${u.host}\r\n\r\n"
        out.write(req.toByteArray()); out.flush()
        val status = WsFrame.readLine(inp) ?: throw java.io.IOException("no reply")
        if (!status.contains(" 101")) throw java.io.IOException("refused: $status")
        while (true) { val l = WsFrame.readLine(inp) ?: break; if (l.isEmpty()) break }
    }

    fun send(text: String) = WsFrame.write(out, 1, text.toByteArray(), true)

    /** The next text message, or null if none arrives within [timeoutMs]. */
    fun receive(timeoutMs: Int): String? {
        sock.soTimeout = timeoutMs
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                val (op, d) = WsFrame.read(inp)
                when (op) {
                    1, 2 -> return String(d)
                    8 -> throw java.io.IOException("closed by TV")
                    9 -> WsFrame.write(out, 10, d, true)
                }
            } catch (_: SocketTimeoutException) { return null }
        }
        return null
    }

    fun close() { try { WsFrame.write(out, 8, ByteArray(0), true) } catch (_: Exception) {}; try { sock.close() } catch (_: Exception) {} }
}

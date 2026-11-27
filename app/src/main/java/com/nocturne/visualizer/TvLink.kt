package com.nocturne.visualizer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The phone side of "Cast to TV". The phone keeps doing all the listening and analysis; it serves the TV page
 * on the local Wi-Fi and streams only the analysis (about 1.5 KB) 60 times a second. The TV draws at its own resolution.
 */
class TvLink(private val ctx: Context, private val brain: Brain, private val renderer: Renderer) {
    companion object { const val FLOATS = 112; const val BYTES = 1024; const val PACKET = FLOATS * 4 + BYTES }

    @Volatile var port = 0; private set
    /** What the TV reports about itself once a second: frame rate and resolution. */
    @Volatile var tvInfo = ""
    @Volatile var running = false; private set
    val tvCount get() = clients.size
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null
    private val t0 = System.nanoTime()

    private class Client(val sock: Socket, val out: OutputStream) { val q = ArrayBlockingQueue<ByteArray>(2) }

    fun url(): String? = localIp()?.let { "http://$it:$port/" }

    fun start() {
        if (running) return
        var s: ServerSocket? = null
        for (p in intArrayOf(8765, 8766, 8767, 0)) { try { s = ServerSocket(p); break } catch (_: Exception) {} }
        server = s ?: return; port = s.localPort; running = true
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nocturne:tv").also { it.acquire() }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nocturne:tv").also { it.acquire(6 * 3600_000L) }
        } catch (_: Exception) {}
        Thread({ acceptLoop(s) }, "tv-accept").start()
        Thread({ streamLoop() }, "tv-stream").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        for (c in clients) try { c.sock.close() } catch (_: Exception) {}
        clients.clear()
        try { wifiLock?.release() } catch (_: Exception) {}; try { wake?.release() } catch (_: Exception) {}
    }

    // ---------------- the stream ----------------

    private fun streamLoop() {
        val buf = ByteBuffer.allocate(PACKET).order(ByteOrder.LITTLE_ENDIAN)
        var lastN = System.nanoTime()
        while (running) {
            val startN = System.nanoTime()
            if (clients.isNotEmpty()) {
                val dt = min(0.1f, (startN - lastN) / 1e9f); lastN = startN
                val packet: ByteArray
                synchronized(brain) {
                    // when the phone's own screen isn't drawing (screen off, another app in front) the analysis runs here
                    if (startN - renderer.lastFrameN > 150_000_000L) brain.update(dt, startN / 1e6)
                    packet = pack(buf, startN)
                }
                for (c in clients) { if (!c.q.offer(packet)) { c.q.poll(); c.q.offer(packet) } }
            } else lastN = startN
            val left = 16_600_000L - (System.nanoTime() - startN)
            if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
        }
    }

    private fun pack(buf: ByteBuffer, nowN: Long): ByteArray {
        val b = brain; val L = b.lev; val H = b.hit; val C = b.clock; val S = Settings
        val now = nowN / 1e6
        val f = FloatArray(FLOATS)
        f[0] = 1f; f[1] = renderer.style.toFloat(); f[2] = renderer.picture.toFloat(); f[3] = ((nowN - t0) / 1e9).toFloat()
        f[4] = L[0]; f[5] = L[1]; f[6] = L[2]; f[7] = H[0]; f[8] = H[1]; f[9] = H[2]; f[10] = C[0]; f[11] = C[1]; f[12] = C[2]
        f[13] = b.core; f[14] = if (b.kickT > 0) min(9.0, (now - b.kickT) / 1000).toFloat() else 9f
        f[15] = b.hsX; f[16] = b.hsV + b.hsB; f[17] = b.hsSpin; f[18] = b.hsE
        f[19] = b.strings.toFloat(); f[20] = b.waveK; f[21] = min(1.8f, L[0] + H[0] * 0.45f + b.chLev[10] * 0.35f)
        f[22] = b.Eg; f[23] = b.int_; f[24] = b.aggr; f[25] = b.assemble; f[26] = b.bgT; f[27] = if (b.live) b.E else 0f
        f[28] = b.shake[0]; f[29] = b.shake[1]; f[30] = b.E; f[31] = b.tempo; f[32] = b.red; f[33] = b.level
        f[34] = S.bars.toFloat(); f[35] = when (S.layout) { "center" -> 1f; "top" -> 2f; else -> 0f }
        f[36] = if (S.bass == "middle") 1f else 0f; f[37] = if (S.seg) 1f else 0f
        f[38] = when (S.color) { "palette" -> 1f; "heat" -> 2f; "rainbow" -> 3f; else -> 0f }; f[39] = if (S.peaks == "off") 0f else 1f
        f[40] = S.trip; f[41] = if (S.aggcolor) 1f else 0f; f[42] = if (S.peakred) 1f else 0f
        f[43] = when (S.trails) { "off" -> 0f; "long" -> 1.6f; else -> 1f }; f[44] = if (S.flash) 1f else 0f; f[45] = when (S.tvRes) { "fhd" -> 1f; "4k" -> 2f; else -> 0f }
        System.arraycopy(b.chLev, 0, f, 48, 11); System.arraycopy(b.chHit, 0, f, 59, 11)
        System.arraycopy(b.strL, 0, f, 70, 7); System.arraycopy(b.strP, 0, f, 77, 7); System.arraycopy(b.strHit, 0, f, 84, 7)
        System.arraycopy(b.strAge, 0, f, 91, 7); System.arraycopy(b.strWK, 0, f, 98, 7); System.arraycopy(b.strGlow, 0, f, 105, 7)
        buf.clear(); for (x in f) buf.putFloat(x)
        buf.put(b.specBytes); buf.put(b.peakBytes); buf.put(b.waveBytes); buf.put(b.toneBytes); buf.put(b.toneSBytes)
        return buf.array().copyOf()
    }

    // ---------------- the little web server ----------------

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val sock = try { s.accept() } catch (_: Exception) { break }
            Thread({ handle(sock) }, "tv-conn").start()
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true; sock.soTimeout = 15000
            val inp = sock.getInputStream().buffered(); val out = sock.getOutputStream()
            val req = WsFrame.readLine(inp) ?: return sock.close()
            val headers = HashMap<String, String>()
            while (true) { val l = WsFrame.readLine(inp) ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val path = req.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            if (path == "/ws" && headers["upgrade"]?.lowercase() == "websocket") return websocket(sock, inp, out, headers["sec-websocket-key"] ?: "")
            val asset = when {
                path == "/" || path == "/index.html" -> "tv/index.html"
                path == "/pictures.json" -> "pictures.json"
                path == "/awake.mp4" || path == "/awake.webm" -> "tv" + path
                path.startsWith("/shaders/") || path.startsWith("/pics/") -> path.substring(1)
                else -> null
            }
            val data = try { asset?.takeIf { !it.contains("..") }?.let { ctx.assets.open(it).use(InputStream::readBytes) } } catch (_: Exception) { null }
            if (data == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); sock.close(); return }
            val type = when { asset!!.endsWith(".html") -> "text/html; charset=utf-8"; asset.endsWith(".json") -> "application/json"; asset.endsWith(".jpg") -> "image/jpeg"; asset.endsWith(".png") -> "image/png"; asset.endsWith(".mp4") -> "video/mp4"; asset.endsWith(".webm") -> "video/webm"; else -> "text/plain; charset=utf-8" }
            // video players ask for byte ranges
            val range = headers["range"]?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (range != null) {
                val a = range.groupValues[1].toIntOrNull() ?: 0; val z = min(data.size - 1, range.groupValues[2].toIntOrNull() ?: (data.size - 1))
                out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Range: bytes $a-$z/${data.size}\r\nContent-Length: ${z - a + 1}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(data, a, z - a + 1)
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: ${data.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(data)
            }
            out.flush(); sock.close()
        } catch (_: Exception) { try { sock.close() } catch (_: Exception) {} }
    }

    private fun websocket(sock: Socket, inp: InputStream, out: OutputStream, key: String) {
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WsFrame.accept(key)}\r\n\r\n").toByteArray()); out.flush()
        sock.soTimeout = 0; sock.sendBufferSize = 64 * 1024
        val c = Client(sock, out); clients.add(c)
        // the TV only ever sends pings and closes; read them on their own thread
        Thread({
            try { while (true) { val (op, d) = WsFrame.read(inp); if (op == 8) break; if (op == 9) synchronized(c) { WsFrame.write(out, 10, d, false) }
                if (op == 1) try { val j = org.json.JSONObject(String(d)); tvInfo = "${j.optInt("fps")} fps · ${j.optInt("w")}×${j.optInt("h")}" + (if (j.optDouble("scale", 1.0) < 1) " · effects at ${(j.optDouble("scale") * 100).toInt()}%" else "") } catch (_: Exception) {} } } catch (_: Exception) {}
            clients.remove(c); try { sock.close() } catch (_: Exception) {}
        }, "tv-read").start()
        try {
            while (running && clients.contains(c)) {
                val p = c.q.poll(500, TimeUnit.MILLISECONDS) ?: continue
                synchronized(c) { WsFrame.write(out, 2, p, false) }
            }
        } catch (_: Exception) {}
        clients.remove(c); try { sock.close() } catch (_: Exception) {}
    }

    // ---------------- this phone's address on the Wi-Fi ----------------

    fun localIp(): String? {
        try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val ordered = all.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
            for (ni in ordered) {
                if (!ni.isUp || ni.isLoopback) continue
                if (ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") || ni.name.startsWith("tun")) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            }
        } catch (_: Exception) {}
        return null
    }
}

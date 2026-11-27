package com.nocturne.visualizer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

class Tv(val ip: String, var name: String, val brand: String)   // brand: "lg", "samsung" or "other"

/**
 * Finds TVs on the Wi-Fi (the same way YouTube's cast list does, over SSDP) and opens the Nocturne page on them.
 * LG webOS: the TV's own remote-control service on port 3001. Samsung Tizen: its remote-control service on port 8002.
 * Both ask once on the TV ("Allow"); after that the app remembers the key the TV gave it.
 */
class TvCast(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /** Searches for about [ms] milliseconds and reports each TV as it is found. Blocking: call from a background thread. */
    fun discover(ms: Long = 3500, found: (Tv) -> Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("nocturne-ssdp").apply { setReferenceCounted(false); acquire() }
        val seen = HashSet<String>()
        try {
            MulticastSocket().use { s ->
                s.soTimeout = 400; s.timeToLive = 4
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf("urn:lge-com:service:webos-second-screen:1", "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:dial-multiscreen-org:service:dial:1", "urn:schemas-upnp-org:device:MediaRenderer:1")
                fun search() { for (st in targets) {
                    val m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    try { s.send(DatagramPacket(m, m.size, group, 1900)) } catch (_: Exception) {}
                } }
                search()
                val end = System.currentTimeMillis() + ms; var again = System.currentTimeMillis() + 1200
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() > again) { search(); again = Long.MAX_VALUE }
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val ip = p.address.hostAddress ?: continue
                    val text = String(p.data, 0, p.length)
                    val h = text.lines().mapNotNull { l -> val i = l.indexOf(':'); if (i > 0) l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim() else null }.toMap()
                    val all = text.lowercase()
                    var brand = when { all.contains("webos") || all.contains("lge") -> "lg"; all.contains("samsung") || all.contains("tizen") -> "samsung"; else -> "other" }
                    if (!seen.add(ip + brand)) continue
                    Thread {
                        var name = ""
                        try {
                            val loc = h["location"]
                            if (loc != null) {
                                val c = URL(loc).openConnection() as HttpURLConnection; c.connectTimeout = 1500; c.readTimeout = 1500
                                val xml = c.inputStream.bufferedReader().use { it.readText() }
                                name = Regex("<friendlyName>([^<]*)</friendlyName>").find(xml)?.groupValues?.get(1) ?: ""
                                val maker = (Regex("<manufacturer>([^<]*)</manufacturer>").find(xml)?.groupValues?.get(1) ?: "").lowercase()
                                if (brand == "other") brand = if (maker.contains("lg")) "lg" else if (maker.contains("samsung")) "samsung" else "other"
                            }
                        } catch (_: Exception) {}
                        if (brand == "other" && probeSamsung(ip)?.let { name = name.ifEmpty { it }; true } == true) brand = "samsung"
                        if (brand != "other") found(Tv(ip, unescape(name).ifEmpty { if (brand == "lg") "LG TV" else "Samsung TV" }, brand))
                    }.start()
                }
            }
        } catch (_: Exception) {} finally { lock.release() }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Samsung TVs describe themselves at port 8001; returns the TV's name or null. */
    fun probeSamsung(ip: String): String? = try {
        val c = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection; c.connectTimeout = 1200; c.readTimeout = 1500
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        j.optJSONObject("device")?.optString("name")?.ifEmpty { null } ?: j.optString("name").ifEmpty { "Samsung TV" }
    } catch (_: Exception) { null }

    /**
     * Opens [url] on the TV. [say] reports progress ("Look at the TV and choose Allow"); [arrived] tells whether the TV
     * has loaded the page (it connects back to the phone). Blocking. Returns null on success, else the problem in plain words.
     */
    fun open(tv: Tv, url: String, arrived: () -> Boolean, say: (String) -> Unit): String? = when (tv.brand) {
        "lg" -> openLg(tv, url, arrived, say)
        "samsung" -> openSamsung(tv, url, arrived, say)
        else -> "This TV isn't supported for casting"
    }

    /** Waits up to [ms] for the TV to load the page. */
    private fun waitArrive(arrived: () -> Boolean, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (arrived()) return true; Thread.sleep(250) }
        return arrived()
    }

    private val fallback = "Couldn't open it on the TV automatically. Open the TV's web browser and type the address below."

    // ---------------- LG webOS ----------------

    private fun lgConnect(ip: String): WsClient? {
        for (addr in listOf("wss://$ip:3001/", "ws://$ip:3000/")) { try { return WsClient(addr).also { it.connect() } } catch (_: Exception) {} }
        return null
    }

    /** Pairs (the TV asks Allow once) and returns a connection ready for commands, or an error. */
    private fun lgRegister(tv: Tv, say: (String) -> Unit): Pair<WsClient?, String?> {
        val end = System.currentTimeMillis() + 70_000
        var tries = 0
        while (System.currentTimeMillis() < end && tries < 4) {
            tries++
            val c = lgConnect(tv.ip) ?: return null to "Couldn't reach the LG TV. Check it's on and on the same Wi-Fi, and that \"LG Connect Apps\" is on in its network settings."
            try {
                val reg = JSONObject(ctx.assets.open("tv/lg_register.json").bufferedReader().use { it.readText() })
                prefs.getString("lg:" + tv.ip, null)?.let { reg.put("client-key", it) }
                c.send(JSONObject().put("type", "register").put("id", "reg$tries").put("payload", reg).toString())
                while (System.currentTimeMillis() < end) {
                    val m = c.receive(2000) ?: continue
                    val j = JSONObject(m)
                    when {
                        j.optString("type") == "registered" -> { j.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("lg:" + tv.ip, it).apply() }; return c to null }
                        j.optJSONObject("payload")?.optString("pairingType") == "PROMPT" -> say("Look at the TV and choose Allow")
                        j.optString("type") == "error" -> {
                            // a remembered key the TV no longer accepts: forget it and ask again
                            if (prefs.contains("lg:" + tv.ip)) { prefs.edit().remove("lg:" + tv.ip).apply(); throw java.io.IOException("stale key") }
                            c.close(); return null to "The TV said no (${j.optString("error")}). Try again and choose Allow on the TV."
                        }
                    }
                }
            } catch (e: Exception) { c.close(); say("The TV closed the connection, trying again…"); Thread.sleep(800) }
        }
        return null to "The TV kept closing the connection. Try again and choose Allow on the TV, or use the address below."
    }

    private fun openLg(tv: Tv, url: String, arrived: () -> Boolean, say: (String) -> Unit): String? {
        val (c0, err) = lgRegister(tv, say)
        var c = c0 ?: return err
        say("Opening Nocturne on the TV…")
        // three ways to ask webOS to open a web page; stop as soon as the TV loads it
        val asks = listOf(
            "ssap://system.launcher/open" to JSONObject().put("target", url),
            "ssap://system.launcher/launch" to JSONObject().put("id", "com.webos.app.browser").put("target", url),
            "ssap://system.launcher/launch" to JSONObject().put("id", "com.webos.app.browser").put("params", JSONObject().put("target", url)))
        try {
            for ((k, a) in asks.withIndex()) {
                try { c.send(JSONObject().put("type", "request").put("id", "open$k").put("uri", a.first).put("payload", a.second).toString()); waitFor(c, "open$k") }
                catch (_: Exception) { c.close(); c = lgRegister(tv, say).first ?: break }
                if (waitArrive(arrived, 9000)) return null
            }
        } finally { c.close() }
        return if (arrived()) null else fallback
    }

    private fun waitFor(c: WsClient, id: String): JSONObject? {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) { val m = c.receive(1500) ?: continue; val j = JSONObject(m); if (j.optString("id") == id) return j }
        return null
    }

    // ---------------- Samsung Tizen ----------------

    private fun ssConnect(ip: String, token: String?): WsClient? {
        val name = URLEncoder.encode(Base64.encodeToString("Nocturne".toByteArray(), Base64.NO_WRAP), "UTF-8")
        for (addr in listOf("wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=$it" } ?: ""),
                            "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$name")) {
            try { return WsClient(addr).also { it.connect(5000) } } catch (_: Exception) {}
        }
        return null
    }

    /** Connects; the TV asks Allow the first time. Some models close the connection while asking, so it reconnects until allowed. */
    private fun ssRegister(tv: Tv, say: (String) -> Unit): Pair<WsClient?, String?> {
        val end = System.currentTimeMillis() + 60_000
        var tries = 0
        while (System.currentTimeMillis() < end && tries < 8) {
            tries++
            val token = prefs.getString("ss:" + tv.ip, null)
            val c = ssConnect(tv.ip, token) ?: return null to "Couldn't reach the Samsung TV. Check it's on and on the same Wi-Fi."
            say(if (token == null) "Look at the TV and choose Allow" else "Connecting…")
            try {
                while (System.currentTimeMillis() < end) {
                    val m = c.receive(2000) ?: continue
                    val j = JSONObject(m)
                    when (j.optString("event")) {
                        "ms.channel.connect" -> { j.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("ss:" + tv.ip, it).apply() }; return c to null }
                        "ms.channel.unauthorized" -> { c.close(); return null to "The TV didn't allow Nocturne. On the TV: Settings › General › External Device Manager › Device Connection Manager › Device List, allow \"Nocturne\" (or delete it there), then try again." }
                        "ms.channel.timeOut" -> throw java.io.IOException("timeout")
                    }
                }
            } catch (e: Exception) {
                c.close()
                // a remembered token the TV no longer knows makes it hang up: drop it and ask again
                if (token != null) prefs.edit().remove("ss:" + tv.ip).apply()
                say("Waiting for the TV… choose Allow on the TV if it asks")
                Thread.sleep(1200)
            }
        }
        return null to "The TV kept closing the connection. Try again and choose Allow on the TV, or use the address below."
    }

    private fun openSamsung(tv: Tv, url: String, arrived: () -> Boolean, say: (String) -> Unit): String? {
        fun launch(type: String) = JSONObject().put("method", "ms.channel.emit").put("params", JSONObject().put("event", "ed.apps.launch").put("to", "host")
            .put("data", JSONObject().put("appId", "org.tizen.browser").put("action_type", type).put("metaTag", url))).toString()
        // different Samsung years accept different launch types; try each until the TV loads the page
        for (type in listOf("NATIVE_LAUNCH", "DEEP_LINK")) {
            val (c, err) = ssRegister(tv, say)
            if (c == null) return err
            say("Opening Nocturne on the TV…")
            try { c.send(launch(type)); c.receive(1500) } catch (_: Exception) {}   // the TV may hang up after launching; that's fine
            val ok = waitArrive(arrived, 10_000)
            c.close()
            if (ok) return null
        }
        return fallback
    }

    fun forget(tv: Tv) { prefs.edit().remove("lg:" + tv.ip).remove("ss:" + tv.ip).apply() }
}

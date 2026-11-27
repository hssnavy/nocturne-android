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

    /** Opens [url] on the TV. [say] reports progress ("Look at the TV and choose Allow"). Blocking. Returns null on success, else the problem. */
    fun open(tv: Tv, url: String, say: (String) -> Unit): String? = when (tv.brand) {
        "lg" -> openLg(tv, url, say)
        "samsung" -> openSamsung(tv, url, say)
        else -> "This TV isn't supported for casting"
    }

    // ---------------- LG webOS ----------------

    private fun openLg(tv: Tv, url: String, say: (String) -> Unit): String? {
        var ws: WsClient? = null
        for (addr in listOf("wss://${tv.ip}:3001/", "ws://${tv.ip}:3000/")) {
            try { ws = WsClient(addr).also { it.connect() }; break } catch (_: Exception) { ws = null }
        }
        val c = ws ?: return "Couldn't reach the LG TV. Check it's on and on the same Wi-Fi, and that \"LG Connect Apps\" is on in its network settings."
        try {
            val reg = JSONObject(ctx.assets.open("tv/lg_register.json").bufferedReader().use { it.readText() })
            prefs.getString("lg:" + tv.ip, null)?.let { reg.put("client-key", it) }
            c.send(JSONObject().put("type", "register").put("id", "reg0").put("payload", reg).toString())
            val end = System.currentTimeMillis() + 60_000
            var ok = false
            while (System.currentTimeMillis() < end && !ok) {
                val m = c.receive(2000) ?: continue
                val j = JSONObject(m)
                when {
                    j.optString("type") == "registered" -> { j.optJSONObject("payload")?.optString("client-key")?.let { prefs.edit().putString("lg:" + tv.ip, it).apply() }; ok = true }
                    j.optJSONObject("payload")?.optString("pairingType") == "PROMPT" -> say("Look at the TV and choose Allow")
                    j.optString("type") == "error" -> return "The TV said no: " + j.optString("error")
                }
            }
            if (!ok) return "The TV didn't answer in time. Try again and choose Allow on the TV."
            say("Opening Nocturne on the TV…")
            // open the page in the TV's web browser
            c.send(JSONObject().put("type", "request").put("id", "open1").put("uri", "ssap://system.launcher/open").put("payload", JSONObject().put("target", url)).toString())
            val r = waitFor(c, "open1")
            if (r == null || r.optString("type") == "error" || r.optJSONObject("payload")?.optBoolean("returnValue", true) == false) {
                c.send(JSONObject().put("type", "request").put("id", "open2").put("uri", "ssap://system.launcher/launch")
                    .put("payload", JSONObject().put("id", "com.webos.app.browser").put("target", url).put("params", JSONObject().put("target", url))).toString())
                val r2 = waitFor(c, "open2")
                if (r2 == null || r2.optString("type") == "error") return "The TV wouldn't open its browser" + (r2?.optString("error")?.let { ": $it" } ?: "")
            }
            return null
        } catch (e: Exception) { return "Lost the connection to the TV (${e.message})" } finally { c.close() }
    }

    private fun waitFor(c: WsClient, id: String): JSONObject? {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) { val m = c.receive(1500) ?: continue; val j = JSONObject(m); if (j.optString("id") == id) return j }
        return null
    }

    // ---------------- Samsung Tizen ----------------

    private fun openSamsung(tv: Tv, url: String, say: (String) -> Unit): String? {
        val name = Base64.encodeToString("Nocturne".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString("ss:" + tv.ip, null)
        var c: WsClient? = null; var err = ""
        for (addr in listOf("wss://${tv.ip}:8002/api/v2/channels/samsung.remote.control?name=" + URLEncoder.encode(name, "UTF-8") + (token?.let { "&token=$it" } ?: ""),
                            "ws://${tv.ip}:8001/api/v2/channels/samsung.remote.control?name=" + URLEncoder.encode(name, "UTF-8"))) {
            try { c = WsClient(addr).also { it.connect(5000) }; break } catch (e: Exception) { err = e.message ?: ""; c = null }
        }
        val ws = c ?: return "Couldn't reach the Samsung TV ($err). Check it's on and on the same Wi-Fi."
        try {
            say(if (token == null) "Look at the TV and choose Allow" else "Connecting…")
            val end = System.currentTimeMillis() + 45_000
            var ok = false
            while (System.currentTimeMillis() < end && !ok) {
                val m = ws.receive(2000) ?: continue
                val j = JSONObject(m)
                when (j.optString("event")) {
                    "ms.channel.connect" -> { j.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("ss:" + tv.ip, it).apply() }; ok = true }
                    "ms.channel.unauthorized" -> return "The TV didn't allow Nocturne. On the TV: Settings › General › External Device Manager › Device Connection Manager, allow \"Nocturne\", then try again."
                    "ms.channel.timeOut" -> return "The TV stopped waiting. Try again and choose Allow on the TV."
                }
            }
            if (!ok) return "The TV didn't answer in time. Try again and choose Allow on the TV."
            say("Opening Nocturne on the TV…")
            fun launch(type: String) = JSONObject().put("method", "ms.channel.emit").put("params", JSONObject().put("event", "ed.apps.launch").put("to", "host")
                .put("data", JSONObject().put("appId", "org.tizen.browser").put("action_type", type).put("metaTag", url))).toString()
            ws.send(launch("NATIVE_LAUNCH"))
            // older and newer models answer differently; if the TV reports an error, try the other way of launching
            val until = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < until) {
                val m = ws.receive(800) ?: continue
                val j = JSONObject(m)
                if (j.optString("event").contains("error", true) || (j.optJSONObject("data")?.optInt("code", 200) ?: 200) >= 400) { ws.send(launch("DEEP_LINK")); break }
            }
            return null
        } catch (e: Exception) { return "Lost the connection to the TV (${e.message})" } finally { ws.close() }
    }

    fun forget(tv: Tv) { prefs.edit().remove("lg:" + tv.ip).remove("ss:" + tv.ip).apply() }
}

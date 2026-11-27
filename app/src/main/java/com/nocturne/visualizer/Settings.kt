package com.nocturne.visualizer

import android.content.Context
import kotlin.math.max

/** The same settings as the web page, saved on the phone. */
object Settings {
    @Volatile var sens = 1.1f               // worked out from the dial: 1.1 x dialX(sv)
    @Volatile var sv = 5f                   // Sensitivity dial 0-10, 5 = normal
    @Volatile var svL = 5f; @Volatile var svM = 5f; @Volatile var svH = 5f   // lows / mids / highs dials
    /** dial 0-10 -> multiplier: 5 = x1, every 2.5 doubles or halves it (0 = a quarter, 10 = four times) */
    fun dialX(v: Float): Float = Math.pow(2.0, ((v - 5) / 2.5).toDouble()).toFloat()
    fun applySens() { sens = 1.1f * dialX(sv) }
    @Volatile var response = "instant"      // smooth / fast / instant
    @Volatile var bars = 48
    @Volatile var peaks = "fast"            // off / slow / fast
    @Volatile var layout = "bottom"         // bottom / center / top
    @Volatile var bass = "left"             // left / middle
    @Volatile var seg = false
    @Volatile var trip = 0.5f
    @Volatile var aggcolor = true
    @Volatile var peakred = true
    @Volatile var color = "picture"         // picture / palette / heat / rainbow
    @Volatile var trails = "short"          // off / short / long
    @Volatile var flash = true
    @Volatile var shake = true
    @Volatile var quality = "auto"          // auto / balanced / high
    @Volatile var strings = 3
    @Volatile var tvRes = "auto"            // TV picture: auto (smoothest) / fhd / 4k

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sens = p.getFloat("sens", sens)
        sv = if (p.contains("sv")) p.getFloat("sv", 5f) else (5 + 2.5f * (Math.log(max(0.28f, sens) / 1.1) / Math.log(2.0)).toFloat()).coerceIn(0f, 10f)
        svL = p.getFloat("svL", 5f); svM = p.getFloat("svM", 5f); svH = p.getFloat("svH", 5f); applySens(); response = p.getString("response", response)!!; bars = p.getInt("bars", bars)
        peaks = p.getString("peaks", peaks)!!; layout = p.getString("layout", layout)!!; bass = p.getString("bass", bass)!!
        seg = p.getBoolean("seg", seg); trip = p.getFloat("trip", trip); aggcolor = p.getBoolean("aggcolor", aggcolor)
        peakred = p.getBoolean("peakred", peakred); color = p.getString("color", color)!!; trails = p.getString("trails", trails)!!
        flash = p.getBoolean("flash", flash); shake = p.getBoolean("shake", shake); quality = p.getString("quality", quality)!!; tvRes = p.getString("tvRes", tvRes)!!
        strings = p.getInt("strings", ctx.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getInt("strings", strings))
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putFloat("sens", sens).putFloat("sv", sv).putFloat("svL", svL).putFloat("svM", svM).putFloat("svH", svH).putString("response", response).putInt("bars", bars).putString("peaks", peaks)
            .putString("layout", layout).putString("bass", bass).putBoolean("seg", seg).putFloat("trip", trip)
            .putBoolean("aggcolor", aggcolor).putBoolean("peakred", peakred).putString("color", color).putString("trails", trails)
            .putBoolean("flash", flash).putBoolean("shake", shake).putString("quality", quality).putInt("strings", strings).putString("tvRes", tvRes).apply()
    }

    fun reset() {
        sv = 5f; svL = 5f; svM = 5f; svH = 5f; applySens(); response = "instant"; bars = 48; peaks = "fast"; layout = "bottom"; bass = "left"; seg = false; trip = 0.5f
        aggcolor = true; peakred = true; color = "picture"; trails = "short"; flash = true; shake = true; quality = "auto"; strings = 3
    }
}

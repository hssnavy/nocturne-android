package com.nocturne.visualizer

import android.content.Context

/** The same settings as the web page, saved on the phone. */
object Settings {
    @Volatile var sens = 1.1f
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
        sens = p.getFloat("sens", sens); response = p.getString("response", response)!!; bars = p.getInt("bars", bars)
        peaks = p.getString("peaks", peaks)!!; layout = p.getString("layout", layout)!!; bass = p.getString("bass", bass)!!
        seg = p.getBoolean("seg", seg); trip = p.getFloat("trip", trip); aggcolor = p.getBoolean("aggcolor", aggcolor)
        peakred = p.getBoolean("peakred", peakred); color = p.getString("color", color)!!; trails = p.getString("trails", trails)!!
        flash = p.getBoolean("flash", flash); shake = p.getBoolean("shake", shake); quality = p.getString("quality", quality)!!; tvRes = p.getString("tvRes", tvRes)!!
        strings = p.getInt("strings", ctx.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getInt("strings", strings))
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putFloat("sens", sens).putString("response", response).putInt("bars", bars).putString("peaks", peaks)
            .putString("layout", layout).putString("bass", bass).putBoolean("seg", seg).putFloat("trip", trip)
            .putBoolean("aggcolor", aggcolor).putBoolean("peakred", peakred).putString("color", color).putString("trails", trails)
            .putBoolean("flash", flash).putBoolean("shake", shake).putString("quality", quality).putInt("strings", strings).putString("tvRes", tvRes).apply()
    }

    fun reset() {
        sens = 1.1f; response = "instant"; bars = 48; peaks = "fast"; layout = "bottom"; bass = "left"; seg = false; trip = 0.5f
        aggcolor = true; peakred = true; color = "picture"; trails = "short"; flash = true; shake = true; quality = "auto"; strings = 3
    }
}

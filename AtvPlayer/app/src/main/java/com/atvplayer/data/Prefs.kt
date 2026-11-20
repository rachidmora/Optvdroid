package com.atvplayer.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("atv", Context.MODE_PRIVATE)

    fun loadConfig() = BoxConfig(
        host = sp.getString("host", "") ?: "",
        httpPort = sp.getInt("httpPort", 80),
        streamPort = sp.getInt("streamPort", 8001),
        user = sp.getString("user", "") ?: "",
        pass = sp.getString("pass", "") ?: "",
    )

    fun saveConfig(c: BoxConfig) = sp.edit()
        .putString("host", c.host.trim()).putInt("httpPort", c.httpPort)
        .putInt("streamPort", c.streamPort).putString("user", c.user)
        .putString("pass", c.pass).apply()

    fun loadImage() = ImageSettings(
        saturation = sp.getFloat("sat", 1f),
        contrast = sp.getFloat("con", 1f),
        brightness = sp.getFloat("bri", 0f),
        sharpness = sp.getFloat("shp", 0f),
    )

    fun saveImage(s: ImageSettings) = sp.edit()
        .putFloat("sat", s.saturation).putFloat("con", s.contrast)
        .putFloat("bri", s.brightness).putFloat("shp", s.sharpness).apply()

    var lastRef: String
        get() = sp.getString("lastRef", "") ?: ""
        set(v) { sp.edit().putString("lastRef", v).apply() }
}

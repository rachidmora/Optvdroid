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
        tmdbKey = sp.getString("tmdb", "") ?: "",
    )

    fun saveConfig(c: BoxConfig) = sp.edit()
        .putString("host", c.host.trim()).putInt("httpPort", c.httpPort)
        .putInt("streamPort", c.streamPort).putString("user", c.user)
        .putString("pass", c.pass).putString("tmdb", c.tmdbKey.trim()).apply()

    fun loadImage() = ImageSettings(
        saturation = sp.getFloat("sat", 1f),
        contrast = sp.getFloat("con", 1f),
        brightness = sp.getFloat("bri", 0f),
        sharpness = sp.getFloat("shp", 0f),
    )

    fun saveImage(s: ImageSettings) = sp.edit()
        .putFloat("sat", s.saturation).putFloat("con", s.contrast)
        .putFloat("bri", s.brightness).putFloat("shp", s.sharpness).apply()

    fun loadFavorites(): List<Channel> = try {
        val arr = org.json.JSONArray(sp.getString("favs", "[]"))
        (0 until arr.length()).map { arr.getJSONArray(it) }.mapIndexed { i, a -> Channel(a.getString(0), a.getString(1), i + 1) }
    } catch (_: Exception) { emptyList() }

    fun saveFavorites(list: List<Channel>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONArray().put(it.ref).put(it.name)) }
        sp.edit().putString("favs", arr.toString()).apply()
    }

    fun loadAudioSources(): List<AudioSource> = try {
        val arr = org.json.JSONArray(sp.getString("audioSources", "[]"))
        (0 until arr.length()).map { arr.getJSONArray(it) }.map { AudioSource(it.getString(0), it.getString(1)) }
    } catch (_: Exception) { emptyList() }

    fun saveAudioSources(list: List<AudioSource>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONArray().put(it.name).put(it.url)) }
        sp.edit().putString("audioSources", arr.toString()).apply()
    }

    var autoSubs: Boolean
        get() = sp.getBoolean("autoSubs", false)
        set(v) { sp.edit().putBoolean("autoSubs", v).apply() }

    var subSize: Float
        get() = sp.getFloat("subSize", 30f)
        set(v) { sp.edit().putFloat("subSize", v).apply() }
    var subBottom: Float
        get() = sp.getFloat("subBottom", 56f)
        set(v) { sp.edit().putFloat("subBottom", v).apply() }
    var subStyle: Int
        get() = sp.getInt("subStyle", 0)
        set(v) { sp.edit().putInt("subStyle", v).apply() }
    var subYellow: Boolean
        get() = sp.getBoolean("subYellow", false)
        set(v) { sp.edit().putBoolean("subYellow", v).apply() }

    var lastRef: String
        get() = sp.getString("lastRef", "") ?: ""
        set(v) { sp.edit().putString("lastRef", v).apply() }
}

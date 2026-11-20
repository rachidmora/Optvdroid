package com.atvplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Client minimal pour l'API OpenWebIF d'un récepteur Enigma2 (OpenATV, etc.). */
class OpenWebIfApi(private val cfg: BoxConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val base: String
        get() = "http://${cfg.host}" + if (cfg.httpPort != 80) ":${cfg.httpPort}" else ""

    val authHeader: String?
        get() = if (cfg.user.isNotBlank()) Credentials.basic(cfg.user, cfg.pass) else null

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path)
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} sur $path")
            JSONObject(r.body?.string().orEmpty())
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Liste des bouquets TV. */
    suspend fun bouquets(): List<Bouquet> {
        val arr = getJson("/api/getservices").optJSONArray("services") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Bouquet(it.getString("servicereference"), it.optString("servicename")) }
    }

    /** Chaînes d'un bouquet (les marqueurs/séparateurs sont ignorés). */
    suspend fun channels(bouquetRef: String): List<Channel> {
        val arr = getJson("/api/getservices?sRef=${enc(bouquetRef)}").optJSONArray("services")
            ?: return emptyList()
        var n = 0
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.getString("servicereference").startsWith("1:64:") }
            .map { Channel(it.getString("servicereference"), it.optString("servicename"), ++n) }
    }

    private suspend fun epg(path: String): Map<String, EpgEvent> {
        val arr = getJson(path).optJSONArray("events") ?: return emptyMap()
        val out = HashMap<String, EpgEvent>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val title = e.optString("title")
            val begin = e.optLong("begin_timestamp")
            if (title.isBlank() || begin == 0L) continue
            out[e.optString("sref").trimEnd(':')] = EpgEvent(
                title = title,
                shortDesc = e.optString("shortdesc"),
                longDesc = e.optString("longdesc"),
                begin = begin,
                duration = e.optLong("duration_sec"),
            )
        }
        return out
    }

    /** Programme en cours pour toutes les chaînes du bouquet. */
    suspend fun epgNow(bouquetRef: String) = epg("/api/epgnow?bRef=${enc(bouquetRef)}")

    /** Programme suivant pour toutes les chaînes du bouquet. */
    suspend fun epgNext(bouquetRef: String) = epg("/api/epgnext?bRef=${enc(bouquetRef)}")

    /** Picon : /picon/1_0_19_..._0_0_0.png */
    fun piconUrl(ch: Channel) = "$base/picon/${ch.key.replace(':', '_')}.png"

    /** Flux TS direct (port 8001 par défaut). */
    fun streamUrl(ch: Channel): String {
        val cred = if (cfg.user.isNotBlank()) "${enc(cfg.user)}:${enc(cfg.pass)}@" else ""
        return "http://$cred${cfg.host}:${cfg.streamPort}/${ch.ref}"
    }
}

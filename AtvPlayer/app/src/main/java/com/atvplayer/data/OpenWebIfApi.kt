package com.atvplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import android.text.Html
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

    /** Décode les entités HTML (&quot; &#x27; ...) présentes dans l'EPG. */
    private fun clean(s: String): String {
        var t = s
        repeat(2) { t = Html.fromHtml(t, Html.FROM_HTML_MODE_LEGACY).toString() }
        return t.trim()
    }

    /** "## ( SPORT ) ##  ::|| 27.06.2026 ||::" -> "SPORT" */
    private fun cleanBouquet(s: String): String =
        clean(s).replace(Regex("::\\|\\|.*?\\|\\|::"), " ")
            .replace(Regex("[#«»()\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Liste des bouquets TV. */
    suspend fun bouquets(): List<Bouquet> {
        val arr = getJson("/api/getservices").optJSONArray("services") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Bouquet(it.getString("servicereference"), cleanBouquet(it.optString("servicename")).ifBlank { it.optString("servicename") }) }
    }

    /** Satellites scannés sur le récepteur (Astra 19.2E, Badr 26E...). */
    suspend fun satellites(): List<Bouquet> {
        val root = "1:7:1:0:0:0:0:0:0:0:(type == 1) || (type == 17) || (type == 22) || (type == 25) || (type == 31) || (type == 134) || (type == 195) FROM SATELLITES ORDER BY satellitePosition"
        val arr = getJson("/api/getservices?sRef=${enc(root)}").optJSONArray("services") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Bouquet(it.getString("servicereference"), "📡 " + clean(it.optString("servicename")), 1) }
    }

    /** Chaînes d'un bouquet ou d'un satellite (marqueurs et dossiers ignorés). */
    suspend fun channels(b: Bouquet): List<Channel> {
        // Satellite : on retire "FROM PROVIDERS" pour obtenir directement tous ses services
        val ref = if (b.kind == 1) b.ref.replace(Regex("\\s*FROM PROVIDERS"), "") else b.ref
        val arr = getJson("/api/getservices?sRef=${enc(ref)}").optJSONArray("services")
            ?: return emptyList()
        var n = 0
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { val r = it.getString("servicereference"); !r.startsWith("1:64:") && !r.startsWith("1:7:") }
            .map { Channel(it.getString("servicereference"), clean(it.optString("servicename")), ++n) }
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
                title = clean(title),
                shortDesc = clean(e.optString("shortdesc")),
                longDesc = clean(e.optString("longdesc")),
                begin = begin,
                duration = e.optLong("duration_sec"),
            )
        }
        return out
    }

    /** Programme en cours / suivant d'une seule chaîne (listes satellite et favoris). */
    suspend fun epgOne(ch: Channel): Pair<EpgEvent?, EpgEvent?> {
        val now = try { epg("/api/epgservicenow?sRef=${enc(ch.ref)}").values.firstOrNull() } catch (_: Exception) { null }
        val next = try { epg("/api/epgservicenext?sRef=${enc(ch.ref)}").values.firstOrNull() } catch (_: Exception) { null }
        return now to next
    }

    /** Programme en cours pour toutes les chaînes du bouquet. */
    suspend fun epgNow(bouquetRef: String) = epg("/api/epgnow?bRef=${enc(bouquetRef)}")

    /** Programme suivant pour toutes les chaînes du bouquet. */
    suspend fun epgNext(bouquetRef: String) = epg("/api/epgnext?bRef=${enc(bouquetRef)}")

    /** Picons candidats, essayés dans l'ordre jusqu'à ce qu'un existe. */
    fun piconUrls(ch: Channel): List<String> {
        val sref = ch.key.replace(':', '_')
        val list = mutableListOf("$base/picon/$sref.png")
        // Services HD/UHD (type 19, 25, 31…) : les picons sont souvent nommés avec le type 1
        run {
            val p = ch.key.split(':')
            if (p.size > 3 && p[0] == "1" && p[2] != "1") {
                list += "$base/picon/${(listOf("1", p[1], "1") + p.drop(3)).joinToString("_")}.png"
            }
        }
        // IPTV (4097/5001/5002...) : les picons sont souvent nommés comme un service DVB "1_"
        val parts = ch.key.split(':')
        if (parts.isNotEmpty() && parts[0] != "1") {
            list += "$base/picon/${(listOf("1") + parts.drop(1)).joinToString("_")}.png"
        }
        // Nom SNP (ex: "sky sport 1 hd" -> skysport1hd)
        val snp = ch.name.lowercase().replace("&", "and").replace("+", "plus").replace(Regex("[^a-z0-9]"), "")
        if (snp.isNotBlank()) list += "$base/picon/$snp.png"
        // Sans suffixe HD/UHD/4K
        val snp2 = ch.name.lowercase().replace(Regex("\\b(hd|uhd|4k|fhd)\\b"), "").replace("&", "and").replace("+", "plus").replace(Regex("[^a-z0-9]"), "")
        if (snp2.isNotBlank() && snp2 != snp) list += "$base/picon/$snp2.png"
        list += "$base/picon/${ch.name}.png"
        return list
    }

    /** Flux TS direct (port 8001 par défaut). */
    fun streamUrl(ch: Channel): String {
        val cred = if (cfg.user.isNotBlank()) "${enc(cfg.user)}:${enc(cfg.pass)}@" else ""
        return "http://$cred${cfg.host}:${cfg.streamPort}/${ch.ref}"
    }
}

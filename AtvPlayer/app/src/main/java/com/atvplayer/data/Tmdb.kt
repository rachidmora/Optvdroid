package com.atvplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Affiches des programmes. TMDB si une clé (v3 ou jeton v4) est configurée,
 * sinon (ou si TMDB ne trouve rien) recherche gratuite sans clé via iTunes.
 */
class Tmdb(private val key: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    /** Dernière erreur TMDB (clé refusée…), pour l'afficher à l'utilisateur. */
    @Volatile var lastError: String? = null

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun candidates(title: String): List<String> {
        val cleaned = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("\\b(S\\d+\\s*E\\d+|Staffel \\d+|Saison \\d+|Folge \\d+|Episode \\d+|HD|VF|VOSTFR)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        return listOfNotNull(
            cleaned,
            cleaned.substringBefore(':').trim().takeIf { cleaned.contains(':') },
            cleaned.substringBefore(" - ").trim().takeIf { cleaned.contains(" - ") },
        ).filter { it.length >= 3 }.distinct()
    }

    /** URL de l'affiche (portrait), null si introuvable. */
    suspend fun poster(title: String): String? = withContext(Dispatchers.IO) {
        val qs = candidates(title)
        for (q in qs) {
            if (key.isNotBlank()) tmdb(q)?.let { return@withContext it }
        }
        for (q in qs) {
            itunes(q)?.let { return@withContext it }
        }
        null
    }

    private fun tmdb(q: String): String? = try {
        val bearer = key.startsWith("eyJ")
        val base = "https://api.themoviedb.org/3/search/multi?include_adult=false&language=fr-FR&query=${enc(q)}"
        val url = if (bearer) base else "$base&api_key=${enc(key)}"
        val req = Request.Builder().url(url).apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) { lastError = "Clé TMDB refusée (401)"; return null }
            if (!r.isSuccessful) { lastError = "TMDB : HTTP ${r.code}"; return null }
            lastError = null
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("results")
            var found: String? = null
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("media_type") == "person") continue
                val path = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
                found = "https://image.tmdb.org/t/p/w342$path"; break
            }
            found
        }
    } catch (e: Exception) { lastError = "TMDB inaccessible : ${e.message}"; null }

    private fun itunes(q: String): String? {
        val nq = norm(q)
        for (media in listOf("movie", "tvShow")) {
            try {
                val entity = if (media == "movie") "movie" else "tvSeason"
                val url = "https://itunes.apple.com/search?term=${enc(q)}&media=$media&entity=$entity&limit=5"
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return@use
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = norm(o.optString("trackName").ifBlank { o.optString("collectionName") })
                        val art = o.optString("artworkUrl100")
                        if (art.isBlank() || name.isBlank()) continue
                        if (name.contains(nq) || nq.contains(name) || name.startsWith(nq.substringBefore(' '))) {
                            return art.replace(Regex("/\\d+x\\d+bb"), "/400x600bb")
                        }
                    }
                }
            } catch (_: Exception) { /* essai suivant */ }
        }
        return null
    }
}

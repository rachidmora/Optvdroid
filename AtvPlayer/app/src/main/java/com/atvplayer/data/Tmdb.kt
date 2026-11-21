package com.atvplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Posters via TMDB (clé API v3 ou jeton v4 fournis par l'utilisateur). */
class Tmdb(private val key: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** URL du poster (portrait) ou du fond pour un titre de programme, null si introuvable. */
    suspend fun poster(title: String): String? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext null
        val cleaned = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("\\b(S\\d+\\s*E\\d+|Staffel \\d+|Saison \\d+|Folge \\d+|Episode \\d+)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val candidates = listOfNotNull(
            cleaned.takeIf { it.length >= 3 },
            cleaned.substringBefore(':').trim().takeIf { cleaned.contains(':') && it.length >= 3 },
        ).distinct()
        for (q in candidates) {
            val url = search(q)
            if (url != null) return@withContext url
        }
        null
    }

    private fun search(q: String): String? {
        val bearer = key.startsWith("eyJ")
        val base = "https://api.themoviedb.org/3/search/multi?include_adult=false&query=${enc(q)}"
        val url = if (bearer) base else "$base&api_key=${enc(key)}"
        val req = Request.Builder().url(url).apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("media_type") == "person") continue
                val path = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
                return "https://image.tmdb.org/t/p/w342$path"
            }
        }
        return null
    }
}

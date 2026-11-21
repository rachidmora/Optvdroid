package com.atvplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atvplayer.data.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import kotlin.concurrent.thread

data class Cue(val start: Long, val end: Long, val text: String)

enum class SubStyle(val label: String) { Osn("OSN"), Box("Boîte"), Plain("Simple") }
enum class Enc(val label: String) { Auto("Auto"), Utf8("UTF-8"), Arabic("Arabe 1256"), Latin("Latin 1252") }

/**
 * "Lecteur" de sous-titres indépendant de VLC : lit un .srt, le démarre au même état que le film
 * (heure de début de l'EPG) puis suit l'horloge. La synchro se règle par décalage.
 */
class SubtitleEngine(private val appCtx: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    var cues by mutableStateOf<List<Cue>>(emptyList()); private set
    var fileName by mutableStateOf(""); private set
    var status by mutableStateOf(""); private set
    var visible by mutableStateOf(true)
    var offsetMs by mutableStateOf(0L); private set
    /** Heure murale (ms) correspondant à 0:00:00 du fichier SRT. */
    var originMs by mutableStateOf(0L); private set
    var enc by mutableStateOf(Enc.Auto); private set
    private var raw: ByteArray? = null

    // Apparence (mémorisée)
    var textSize by mutableStateOf(prefs.subSize)
    var bottomDp by mutableStateOf(prefs.subBottom)
    var style by mutableStateOf(SubStyle.values().getOrElse(prefs.subStyle) { SubStyle.Osn })
    var yellow by mutableStateOf(prefs.subYellow)

    fun saveStyle() {
        prefs.subSize = textSize; prefs.subBottom = bottomDp
        prefs.subStyle = style.ordinal; prefs.subYellow = yellow
    }

    val loaded get() = cues.isNotEmpty()

    // ---------- Chargement ----------
    fun loadUrl(url: String, eventBeginSec: Long?) {
        status = "Téléchargement…"
        thread {
            try {
                val req = Request.Builder().url(url.trim()).build()
                val bytes = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body?.bytes() ?: ByteArray(0)
                }
                main.post { setData(bytes, url.substringAfterLast('/').take(40), eventBeginSec) }
            } catch (e: Exception) {
                main.post { status = "Erreur : ${e.message}" }
            }
        }
    }

    fun loadUri(uri: Uri, eventBeginSec: Long?) {
        status = "Lecture du fichier…"
        thread {
            try {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                main.post { setData(bytes, uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "fichier.srt", eventBeginSec) }
            } catch (e: Exception) {
                main.post { status = "Erreur : ${e.message}" }
            }
        }
    }

    private fun setData(bytes: ByteArray, name: String, eventBeginSec: Long?) {
        raw = bytes; fileName = name; offsetMs = 0
        originMs = if (eventBeginSec != null && eventBeginSec > 0) eventBeginSec * 1000 else System.currentTimeMillis()
        parse()
    }

    fun changeEnc(e: Enc) { enc = e; parse() }

    private fun parse() {
        val b = raw ?: return
        val list = SrtParser.parse(SrtParser.decode(b, enc))
        cues = list
        status = if (list.isEmpty()) "Aucune réplique trouvée (essaie un autre encodage)" else "${list.size} répliques chargées"
    }

    fun clear() { cues = emptyList(); raw = null; fileName = ""; status = ""; offsetMs = 0 }

    // ---------- Horloge / synchro ----------
    fun positionMs(nowMs: Long = System.currentTimeMillis()): Long = nowMs - originMs + offsetMs

    fun cueAt(pos: Long): Cue? {
        val l = cues
        var lo = 0; var hi = l.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = l[mid]
            when {
                pos < c.start -> hi = mid - 1
                pos > c.end -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    fun nudge(deltaMs: Long) { offsetMs += deltaMs }
    fun resetOffset() { offsetMs = 0 }

    /** Le fichier démarre à l'heure de début du programme EPG (même état que le film). */
    fun startFromEpg(eventBeginSec: Long?) {
        originMs = if (eventBeginSec != null && eventBeginSec > 0) eventBeginSec * 1000 else System.currentTimeMillis()
        offsetMs = 0
    }

    /** Le fichier démarre maintenant (la 1re réplique correspond à l'instant présent). */
    fun startNow() { originMs = System.currentTimeMillis(); offsetMs = 0 }

    /** La prochaine réplique du fichier doit s'afficher maintenant (quand tu entends la phrase). */
    fun syncNextCueNow() {
        val pos = positionMs()
        val next = cues.firstOrNull { it.start > pos } ?: return
        offsetMs += next.start - pos
    }
}

object SrtParser {
    fun decode(b: ByteArray, enc: Enc): String {
        val s = when (enc) {
            Enc.Utf8 -> String(b, Charsets.UTF_8)
            Enc.Arabic -> String(b, Charset.forName("windows-1256"))
            Enc.Latin -> String(b, Charset.forName("windows-1252"))
            Enc.Auto -> when {
                b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, Charsets.UTF_16LE)
                b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, Charsets.UTF_16BE)
                else -> try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(b)).toString()
                } catch (_: CharacterCodingException) {
                    String(b, Charset.forName("windows-1256"))   // cas courant des .srt arabes
                }
            }
        }
        return s.removePrefix("﻿")
    }

    private val timeRe = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val tagRe = Regex("<[^>]+>|\\{\\\\[^}]*\\}")

    private fun ms(h: String, m: String, s: String, f: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').take(3).toLong()

    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (blk in blocks) {
            val lines = blk.trim().split('\n')
            val idx = lines.indexOfFirst { it.contains("-->") }
            if (idx < 0) continue
            val m = timeRe.find(lines[idx]) ?: continue
            val g = m.groupValues
            val body = lines.drop(idx + 1).joinToString("\n") { tagRe.replace(it, "").trim() }.trim()
            if (body.isNotEmpty()) out += Cue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), body)
        }
        out.sortBy { it.start }
        return out
    }
}

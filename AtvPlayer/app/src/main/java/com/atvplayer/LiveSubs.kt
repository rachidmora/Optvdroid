package com.atvplayer

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atvplayer.data.Prefs
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// ======================================================================
//  Décodeur de sous-titres télétexte EBU (DVB) lus dans un flux MPEG-TS
// ======================================================================
class TeletextDecoder(
    private val onPage: (String) -> Unit,
    private val onInfo: (String) -> Unit,
) {
    /** Page forcée (ex. 0x150) ; 0 = automatique (selon le PMT). */
    var forcedPage = 0

    private var carry = ByteArray(0)
    private var pmtPid = -1
    private var ttxPid = -1
    private var targetPage = 0
    private var langHint = ""
    private val pat = Section()
    private val pmt = Section()
    private var pes = ByteArray(0)

    private var receiving = false
    private var charset = 0
    private val rows = arrayOfNulls<String>(25)
    private var lastText = ""

    fun reset() {
        carry = ByteArray(0); pmtPid = -1; ttxPid = -1; targetPage = 0; langHint = ""
        pes = ByteArray(0); receiving = false; lastText = ""; rows.fill(null)
    }

    fun feed(d: ByteArray, n: Int) {
        val all = if (carry.isEmpty()) d.copyOf(n) else carry + d.copyOf(n)
        var i = 0
        while (i + 188 <= all.size) {
            if (all[i] != 0x47.toByte() || (i + 188 < all.size && all[i + 188] != 0x47.toByte())) { i++; continue }
            packet(all, i); i += 188
        }
        carry = all.copyOfRange(i, all.size)
    }

    private fun packet(a: ByteArray, o: Int) {
        val pid = ((a[o + 1].toInt() and 0x1F) shl 8) or (a[o + 2].toInt() and 0xFF)
        val pusi = (a[o + 1].toInt() and 0x40) != 0
        val afc = (a[o + 3].toInt() shr 4) and 3
        var p = o + 4
        if (afc and 2 != 0) p += 1 + (a[o + 4].toInt() and 0xFF)
        val end = o + 188
        if (afc and 1 == 0 || p >= end) return
        when {
            pid == 0 && pmtPid < 0 -> pat.add(a, p, end, pusi)?.let { parsePat(it) }
            pid == pmtPid && ttxPid < 0 -> pmt.add(a, p, end, pusi)?.let { parsePmt(it) }
            pid == ttxPid -> {
                if (pusi) { if (pes.isNotEmpty()) processPes(pes); pes = a.copyOfRange(p, end) }
                else if (pes.isNotEmpty()) pes += a.copyOfRange(p, end)
            }
        }
    }

    private class Section {
        var b = ByteArray(0); var need = -1
        fun add(a: ByteArray, p: Int, end: Int, pusi: Boolean): ByteArray? {
            if (pusi) {
                val s = p + 1 + (a[p].toInt() and 0xFF)
                if (s >= end) return null
                b = a.copyOfRange(s, end); need = -1
            } else {
                if (b.isEmpty()) return null
                b += a.copyOfRange(p, end)
            }
            if (need < 0 && b.size >= 3) need = 3 + (((b[1].toInt() and 0x0F) shl 8) or (b[2].toInt() and 0xFF))
            if (need >= 0 && b.size >= need) { val r = b.copyOf(need); b = ByteArray(0); need = -1; return r }
            return null
        }
    }

    private fun parsePat(s: ByteArray) {
        var i = 8
        while (i + 4 <= s.size - 4) {
            val prog = ((s[i].toInt() and 0xFF) shl 8) or (s[i + 1].toInt() and 0xFF)
            val pid = ((s[i + 2].toInt() and 0x1F) shl 8) or (s[i + 3].toInt() and 0xFF)
            if (prog != 0) { pmtPid = pid; return }
            i += 4
        }
    }

    private fun parsePmt(s: ByteArray) {
        if (s.size < 16) return
        val pil = ((s[10].toInt() and 0x0F) shl 8) or (s[11].toInt() and 0xFF)
        var i = 12 + pil
        var best = -1; var bestPage = 0; var bestLang = ""; var anyTtx = false
        while (i + 5 <= s.size - 4) {
            val pid = ((s[i + 1].toInt() and 0x1F) shl 8) or (s[i + 2].toInt() and 0xFF)
            val esLen = ((s[i + 3].toInt() and 0x0F) shl 8) or (s[i + 4].toInt() and 0xFF)
            var d = i + 5
            val dEnd = minOf(i + 5 + esLen, s.size - 4)
            while (d + 2 <= dEnd) {
                val tag = s[d].toInt() and 0xFF
                val len = s[d + 1].toInt() and 0xFF
                if (tag == 0x56) {
                    anyTtx = true
                    var k = d + 2
                    while (k + 5 <= d + 2 + len && k + 5 <= s.size) {
                        val lang = String(s, k, 3, Charsets.ISO_8859_1)
                        val type = (s[k + 3].toInt() shr 3) and 0x1F
                        var mag = s[k + 3].toInt() and 7; if (mag == 0) mag = 8
                        val pg = s[k + 4].toInt() and 0xFF
                        if ((type == 2 || type == 5) && (best < 0 || type == 2 && bestLang.isEmpty())) {
                            best = pid; bestPage = (mag shl 8) or pg; bestLang = lang
                        }
                        k += 5
                    }
                }
                d += 2 + len
            }
            i += 5 + esLen
        }
        if (best >= 0) {
            ttxPid = best; targetPage = if (forcedPage != 0) forcedPage else bestPage; langHint = bestLang
            onInfo("Télétexte détecté : page %X (%s)".format(targetPage, bestLang))
        } else {
            onInfo(if (anyTtx) "Télétexte sans page de sous-titres" else "Pas de télétexte sur cette chaîne")
        }
    }

    private fun rev(b: Byte): Int = Integer.reverse(b.toInt() and 0xFF) ushr 24
    private fun unham(b: Int) = ((b shr 1) and 1) or (((b shr 3) and 1) shl 1) or (((b shr 5) and 1) shl 2) or (((b shr 7) and 1) shl 3)

    private fun processPes(d: ByteArray) {
        if (d.size < 10 || d[0].toInt() != 0 || d[1].toInt() != 0 || d[2].toInt() != 1) return
        var i = 9 + (d[8].toInt() and 0xFF)
        if (i >= d.size) return
        i++ // data_identifier
        while (i + 2 <= d.size) {
            val id = d[i].toInt() and 0xFF
            val len = d[i + 1].toInt() and 0xFF
            if ((id == 0x02 || id == 0x03) && len == 44 && i + 2 + 44 <= d.size) telx(d, i + 2)
            i += 2 + len
        }
    }

    private fun telx(u: ByteArray, o: Int) {
        // u[o] = field/line, u[o+1] = framing code, puis 2 octets d'adresse et 40 octets de données
        val addr = (unham(rev(u[o + 3])) shl 4) or unham(rev(u[o + 2]))
        var m = addr and 7; if (m == 0) m = 8
        val y = (addr shr 3) and 0x1F
        if (y == 0) {
            val pn = (m shl 8) or (unham(rev(u[o + 5])) shl 4) or unham(rev(u[o + 4]))
            if (receiving && (pn shr 8) == (targetPage shr 8)) flush()
            if (targetPage != 0 && pn == targetPage) {
                receiving = true; rows.fill(null)
                charset = (unham(rev(u[o + 4 + 7])) and 0x0E) shr 1
            } else if ((pn shr 8) == (targetPage shr 8)) receiving = false
        } else if (y in 1..23 && receiving && m == (targetPage shr 8)) {
            val sb = StringBuilder(40)
            val cs = if (charset == 0 && langHint == "ger") 1 else if (charset == 0 && (langHint == "fre" || langHint == "fra")) 4 else charset
            for (k in 0 until 40) sb.append(char(rev(u[o + 4 + k]) and 0x7F, cs))
            rows[y] = sb.toString()
        }
    }

    private fun flush() {
        val text = (1..23).mapNotNull { rows[it]?.trim() }.filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+"), " ")
        receiving = false
        if (text != lastText) { lastText = text; onPage(text) }
    }

    private fun char(c: Int, cs: Int): Char {
        if (c < 0x20 || c == 0x7F) return ' '
        when (cs) {
            0 -> if (c == 0x23) return '£'
            1 -> return when (c) { 0x40 -> '§'; 0x5B -> 'Ä'; 0x5C -> 'Ö'; 0x5D -> 'Ü'; 0x60 -> '°'; 0x7B -> 'ä'; 0x7C -> 'ö'; 0x7D -> 'ü'; 0x7E -> 'ß'; else -> c.toChar() }
            4 -> return when (c) { 0x23 -> 'é'; 0x24 -> 'ï'; 0x40 -> 'à'; 0x5B -> 'ë'; 0x5C -> 'ê'; 0x5D -> 'ù'; 0x5E -> 'î'; 0x5F -> '#'; 0x60 -> 'è'; 0x7B -> 'â'; 0x7C -> 'ô'; 0x7D -> 'û'; 0x7E -> 'ç'; else -> c.toChar() }
        }
        return c.toChar()
    }
}

// ======================================================================
//  Relais local : une seule connexion à la box, le flux va à VLC ET au décodeur
// ======================================================================
class TsRelay(private val onData: (ByteArray, Int) -> Unit) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var upstream: okhttp3.Call? = null

    fun start(url: String): String {
        stop()
        val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread(isDaemon = true) {
            try { while (!ss.isClosed) { val s = ss.accept(); thread(isDaemon = true) { serve(s, url) } } } catch (_: Exception) {}
        }
        return "http://127.0.0.1:${ss.localPort}/stream.ts"
    }

    private fun serve(s: Socket, url: String) {
        try {
            s.use {
                val inp = s.getInputStream()
                val hdr = ByteArray(8192); var n = 0
                while (n < hdr.size) {
                    val r = inp.read(hdr, n, 1); if (r < 0) return
                    n += r
                    if (n >= 4 && hdr[n - 4].toInt() == 13 && hdr[n - 3].toInt() == 10 && hdr[n - 2].toInt() == 13 && hdr[n - 1].toInt() == 10) break
                }
                val hu = url.toHttpUrlOrNull() ?: return
                val clean = hu.newBuilder().username("").password("").build()
                val req = Request.Builder().url(clean).apply {
                    if (hu.username.isNotEmpty()) header("Authorization", Credentials.basic(hu.username, hu.password))
                }.build()
                val call = client.newCall(req); upstream = call
                call.execute().use { r ->
                    if (!r.isSuccessful) return
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
                    val src = r.body!!.byteStream(); val buf = ByteArray(32768)
                    while (true) {
                        val k = src.read(buf); if (k < 0) break
                        out.write(buf, 0, k)
                        onData(buf, k)
                    }
                }
            }
        } catch (_: Exception) { /* VLC a fermé, ou la box ne répond plus */ }
    }

    fun stop() {
        try { upstream?.cancel() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
    }
}

// ======================================================================
//  Traduction en direct du télétexte (DeepL) : état + logique
// ======================================================================
class LiveSubs(private val prefs: Prefs) {
    var enabled by mutableStateOf(prefs.ttxEnabled); private set
    var apiKey by mutableStateOf(prefs.deeplKey); private set
    var target by mutableStateOf(prefs.ttxTarget); private set
    var showOriginal by mutableStateOf(prefs.ttxShowOriginal); private set
    var page by mutableStateOf(prefs.ttxPage); private set   // 0 = auto
    var status by mutableStateOf(""); private set
    var original by mutableStateOf<String?>(null); private set
    var translated by mutableStateOf<String?>(null); private set

    val ready: Boolean get() = enabled && apiKey.isNotBlank()

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, String>()
    private val clearTask = Runnable { original = null; translated = null }
    @Volatile private var gen = 0

    private val decoder = TeletextDecoder(
        onPage = { t -> main.post { onPage(t) } },
        onInfo = { t -> main.post { status = t } },
    )
    private val relay = TsRelay { d, n -> synchronized(decoder) { decoder.feed(d, n) } }

    /** Renvoie l'URL à donner à VLC : le relais local si la traduction est active, sinon l'URL d'origine. */
    fun wrap(url: String): String {
        clearNow()
        if (!ready) { relay.stop(); if (enabled) status = "Clé DeepL manquante"; return url }
        synchronized(decoder) { decoder.reset(); decoder.forcedPage = page }
        status = "Recherche du télétexte…"
        return relay.start(url)
    }

    fun stop() { relay.stop(); clearNow() }

    private fun clearNow() { gen++; main.removeCallbacks(clearTask); original = null; translated = null }

    private fun onPage(text: String) {
        main.removeCallbacks(clearTask)
        if (text.isBlank()) { original = null; translated = null; return }
        val g = ++gen
        original = text
        val c = cache[text]
        if (c != null) translated = c else {
            translated = null
            thread(isDaemon = true) {
                val tr = translate(text)
                main.post { if (g == gen && tr != null) { cache[text] = tr; translated = tr } }
            }
        }
        // Le télétexte n'a pas d'heure de fin : on efface selon la longueur du texte
        main.postDelayed(clearTask, (2200L + text.length * 70L).coerceAtMost(8000L))
    }

    private fun translate(text: String): String? = try {
        val key = apiKey.trim()
        val url = if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val body = FormBody.Builder().add("text", text).add("target_lang", target).build()
        val req = Request.Builder().url(url).header("Authorization", "DeepL-Auth-Key $key").post(body).build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 403 -> { main.post { status = "Clé DeepL refusée (403)" }; null }
                r.code == 456 -> { main.post { status = "Quota DeepL épuisé (456)" }; null }
                !r.isSuccessful -> { main.post { status = "DeepL : erreur HTTP ${r.code}" }; null }
                else -> JSONObject(r.body?.string().orEmpty()).getJSONArray("translations").getJSONObject(0).getString("text")
            }
        }
    } catch (e: Exception) { main.post { status = "DeepL inaccessible : ${e.message}" }; null }

    fun changeEnabled(v: Boolean) { enabled = v; prefs.ttxEnabled = v }
    fun changeKey(v: String) { apiKey = v.trim(); prefs.deeplKey = apiKey }
    fun changeTarget(v: String) { target = v; prefs.ttxTarget = v; cache.clear() }
    fun changeShowOriginal(v: Boolean) { showOriginal = v; prefs.ttxShowOriginal = v }
    fun changePage(v: Int) { page = v; prefs.ttxPage = v }
}

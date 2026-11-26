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
data class SubPage(val lang: String, val type: Int, val page: Int) {
    val label: String get() = "%s %X%s".format(lang.uppercase(), page, if (type == 5) " (malentendants)" else "")
}

class TeletextDecoder(
    private val onPage: (String) -> Unit,
    private val onInfo: (String) -> Unit,
    private val onPages: (List<SubPage>) -> Unit,
) {
    /** Page forcée (ex. 0x150) ; 0 = toutes les pages de sous-titres déclarées. */
    var forcedPage = 0

    private var carry = ByteArray(0)
    private val pmtPids = HashSet<Int>()
    private var pmtDone = false
    private var ttxPid = -1
    private var targets = setOf<Int>()
    private var candidates = listOf<SubPage>()
    private val pat = Section()
    private val pmt = Section()
    private var pes = ByteArray(0)

    private var receiving = 0
    private var charset = 0
    private val rows = arrayOfNulls<String>(25)
    private var active = 0
    private var activeAt = 0L
    private var lastText = ""

    // Diagnostic
    private var nBytes = 0L; private var nPmt = 0; private var nPmtPk = 0
    private var nUnits = 0L; private var nHdr = 0L; private var nTarget = 0L; private var nPages = 0
    private var seenPages = LinkedHashSet<Int>()
    private var lastRaw = ""
    fun stats(): String = "flux %d Ko · programmes PAT:%d · PMT:%s (paquets %d) · PID TTX:%s · pages ciblées:%s · unités TTX:%d · en-têtes:%d (cible:%d) · pages vues:%s · sous-titres lus:%d%s".format(
        nBytes / 1024, pmtPids.size, if (nPmt > 0) "ok" else "attente", nPmtPk,
        if (ttxPid >= 0) "0x%X".format(ttxPid) else "aucun",
        if (targets.isNotEmpty()) targets.joinToString(",") { "%X".format(it) } else "-",
        nUnits, nHdr, nTarget, seenPages.take(10).joinToString(",") { "%X".format(it) }, nPages,
        if (lastRaw.isNotEmpty()) " · dernier: $lastRaw" else "")

    fun reset() {
        nBytes = 0; nPmt = 0; nPmtPk = 0; nUnits = 0; nHdr = 0; nTarget = 0; nPages = 0; seenPages = LinkedHashSet(); lastRaw = ""
        carry = ByteArray(0); pmtPids.clear(); pmtDone = false; ttxPid = -1; targets = emptySet(); candidates = emptyList()
        pes = ByteArray(0); receiving = 0; active = 0; lastText = ""; rows.fill(null)
    }

    /** Choix manuel d'une page pour cette chaîne (0 = toutes). */
    fun selectPage(p: Int) {
        forcedPage = p
        if (candidates.isNotEmpty() || ttxPid >= 0) targets = if (p != 0) setOf(p) else candidates.map { it.page }.toSet().ifEmpty { setOf(0x150) }
        receiving = 0; active = 0; rows.fill(null)
    }

    fun feed(d: ByteArray, n: Int) {
        nBytes += n
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
            pid == 0 && pmtPids.isEmpty() -> pat.add(a, p, end, pusi)?.let { parsePat(it) }
            // Le flux contient le PAT de tout le multiplex : on prend le PMT qui arrive vraiment
            pid in pmtPids && !pmtDone -> { nPmtPk++; pmt.add(a, p, end, pusi)?.let { parsePmt(it) } }
            ttxPid < 0 && pusi && pid != 0 && pid !in pmtPids -> sniff(pid, a, p, end)
            pid == ttxPid -> {
                if (pusi) { if (pes.isNotEmpty()) processPes(pes); pes = a.copyOfRange(p, end) }
                else if (pes.isNotEmpty()) pes += a.copyOfRange(p, end)
            }
        }
    }

    /** Repère un flux télétexte sans passer par le PMT : PES 0xBD dont le premier octet est 0x10-0x1F. */
    private fun sniff(pid: Int, a: ByteArray, p: Int, end: Int) {
        if (p + 14 > end) return
        if (a[p].toInt() != 0 || a[p + 1].toInt() != 0 || a[p + 2].toInt() != 1 || (a[p + 3].toInt() and 0xFF) != 0xBD) return
        val st = p + 9 + (a[p + 8].toInt() and 0xFF)
        if (st + 3 > end) return
        val di = a[st].toInt() and 0xFF
        val unit = a[st + 1].toInt() and 0xFF
        val len = a[st + 2].toInt() and 0xFF
        if (di in 0x10..0x1F && (unit == 0x02 || unit == 0x03 || unit == 0xFF) && len == 44) {
            ttxPid = pid
            if (targets.isEmpty()) targets = setOf(if (forcedPage != 0) forcedPage else 0x150)
            onInfo("Télétexte détecté (PID 0x%X) : page %s essayée".format(pid, targets.joinToString(",") { "%X".format(it) }))
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
            if (prog != 0) pmtPids.add(pid)
            i += 4
        }
    }

    private fun parsePmt(s: ByteArray) {
        if (s.size < 16) return
        nPmt++; pmtDone = true
        val pil = ((s[10].toInt() and 0x0F) shl 8) or (s[11].toInt() and 0xFF)
        var i = 12 + pil
        val found = ArrayList<SubPage>()
        var pidOfTtx = -1; var anyTtx = false
        while (i + 5 <= s.size - 4) {
            val pid = ((s[i + 1].toInt() and 0x1F) shl 8) or (s[i + 2].toInt() and 0xFF)
            val esLen = ((s[i + 3].toInt() and 0x0F) shl 8) or (s[i + 4].toInt() and 0xFF)
            var d = i + 5
            val dEnd = minOf(i + 5 + esLen, s.size - 4)
            while (d + 2 <= dEnd) {
                val tag = s[d].toInt() and 0xFF
                val len = s[d + 1].toInt() and 0xFF
                if (tag == 0x56) {
                    anyTtx = true; if (pidOfTtx < 0) pidOfTtx = pid
                    var k = d + 2
                    while (k + 5 <= d + 2 + len && k + 5 <= s.size) {
                        val lang = String(s, k, 3, Charsets.ISO_8859_1)
                        val type = (s[k + 3].toInt() shr 3) and 0x1F
                        var mag = s[k + 3].toInt() and 7; if (mag == 0) mag = 8
                        val pg = s[k + 4].toInt() and 0xFF
                        if (type == 2 || type == 5) found.add(SubPage(lang, type, (mag shl 8) or pg))
                        k += 5
                    }
                }
                d += 2 + len
            }
            i += 5 + esLen
        }
        if (anyTtx) ttxPid = pidOfTtx
        candidates = found
        onPages(found)
        when {
            found.isNotEmpty() -> {
                targets = if (forcedPage != 0) setOf(forcedPage) else found.map { it.page }.toSet()
                onInfo("Télétexte détecté : " + found.joinToString(", ") { it.label })
            }
            anyTtx -> {
                targets = setOf(if (forcedPage != 0) forcedPage else 0x150)
                onInfo("Télétexte détecté, sous-titres non déclarés : page %X essayée".format(targets.first()))
            }
            else -> onInfo("Pas de télétexte sur cette chaîne")
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
            if ((id == 0x02 || id == 0x03) && len == 44 && i + 2 + 44 <= d.size) { nUnits++; telx(d, i + 2) }
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
            nHdr++; if (seenPages.size < 40) seenPages.add(pn)
            if (receiving != 0 && (pn shr 8) == (receiving shr 8)) flush()
            if (pn in targets) {
                receiving = pn; rows.fill(null); nTarget++
                // Jeu de caractères national : les 3 bits C12-C14 sont dans l'ordre inverse
                val c = (unham(rev(u[o + 4 + 7])) and 0x0E) shr 1
                charset = ((c and 1) shl 2) or (c and 2) or ((c shr 2) and 1)
            } else receiving = 0
        } else if (y in 1..23 && receiving != 0 && m == (receiving shr 8)) {
            val sb = StringBuilder(40)
            for (k in 0 until 40) sb.append(char(rev(u[o + 4 + k]) and 0x7F, charset))
            rows[y] = sb.toString()
        }
    }

    private fun flush() {
        val page = receiving
        val text = (1..23).mapNotNull { rows[it]?.trim() }.filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+"), " ")
        receiving = 0
        val now = System.currentTimeMillis()
        if (text.isEmpty()) {
            // Page vide = effacement, seulement pour la page qui sert actuellement
            if (page == active && lastText.isNotEmpty()) { lastText = ""; onPage("") }
            return
        }
        // Plusieurs pages (langues) : on suit celle qui parle, et on ne change que si elle se tait depuis 15 s
        if (active != 0 && page != active && now - activeAt < 15000) return
        active = page; activeAt = now
        if (text != lastText) { lastText = text; nPages++; lastRaw = text.take(40); onPage(text) }
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
    /** Retard d'affichage : le télétexte arrive avant l'image (cache de VLC), on le retient pour caler sur la voix. */
    var delayMs by mutableStateOf(prefs.ttxDelayMs); private set
    private val pending = ArrayList<Runnable>()
    var original by mutableStateOf<String?>(null); private set
    var translated by mutableStateOf<String?>(null); private set

    val ready: Boolean get() = enabled && apiKey.isNotBlank()
    fun debug(): String = synchronized(decoder) { decoder.stats() }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, String>()
    private val clearTask = Runnable { original = null; translated = null }
    @Volatile private var gen = 0

    var pages by mutableStateOf<List<SubPage>>(emptyList()); private set
    var chosenPage by mutableStateOf(0); private set   // 0 = automatique (toutes les pages déclarées)

    private val decoder = TeletextDecoder(
        onPage = { t -> main.post { onPage(t) } },
        onInfo = { t -> main.post { status = t } },
        onPages = { l -> main.post { pages = l } },
    )
    private val relay = TsRelay { d, n -> synchronized(decoder) { decoder.feed(d, n) } }

    /** Renvoie l'URL à donner à VLC : le relais local si la traduction est active, sinon l'URL d'origine. */
    fun wrap(url: String): String {
        clearNow()
        if (!ready) { relay.stop(); if (enabled) status = "Clé DeepL manquante"; return url }
        synchronized(decoder) { decoder.reset(); decoder.forcedPage = page }
        status = "Recherche du télétexte…"; pages = emptyList(); chosenPage = 0
        return relay.start(url)
    }

    /** Choix d'une page de sous-titres pour la chaîne en cours (0 = automatique). */
    fun pickPage(p: Int) { chosenPage = p; synchronized(decoder) { decoder.selectPage(p) }; clearNow() }

    fun stop() { relay.stop(); clearNow() }

    private fun clearNow() {
        gen++; main.removeCallbacks(clearTask); original = null; translated = null
        pending.forEach { main.removeCallbacks(it) }; pending.clear()
    }

    /** Arrivée d'une phrase : on traduit tout de suite, mais on l'affiche avec le retard réglé. */
    private fun onPage(text: String) {
        if (text.isNotBlank() && cache[text] == null) {
            thread(isDaemon = true) { translate(text)?.let { tr -> main.post { cache[text] = tr } } }
        }
        val task = object : Runnable {
            override fun run() { pending.remove(this); show(text) }
        }
        pending.add(task)
        main.postDelayed(task, delayMs.toLong())
    }

    private fun show(text: String) {
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
    fun changeDelay(v: Int) { delayMs = v; prefs.ttxDelayMs = v }
    fun changeShowOriginal(v: Boolean) { showOriginal = v; prefs.ttxShowOriginal = v }
    fun changePage(v: Int) { page = v; prefs.ttxPage = v }
}

package com.atvplayer

import android.graphics.Bitmap
import android.graphics.Color

/** Un flux de sous-titres DVB (images) déclaré dans le PMT. */
data class DvbStream(val pid: Int, val lang: String, val type: Int, val comp: Int, val anc: Int) {
    val hearing: Boolean get() = type in 0x20..0x25
    val label: String get() = lang.uppercase() + (if (hearing) " DVB (malentendants)" else " DVB")
}

/**
 * Décodeur de sous-titres DVB (ETSI EN 300 743) : reconstitue l'image de chaque affichage.
 * Sortie : bitmap noir sur blanc, rognée et agrandie, prête pour l'OCR ; null = écran effacé.
 */
class DvbSubDecoder(private val stream: DvbStream, private val onDisplay: (Bitmap?) -> Unit) {
    private class Region(val w: Int, val h: Int, val clut: Int, val objs: List<IntArray>)
    private val regions = HashMap<Int, Region>()
    private val cluts = HashMap<Int, IntArray>()
    private val objs = HashMap<Int, Pair<ByteArray, ByteArray>>()
    private var pageRegs = listOf<IntArray>()
    var nDisplays = 0; private set

    private class Bits(val b: ByteArray, var p: Int = 0) {
        fun get(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or (((b[p shr 3].toInt() shr (7 - (p and 7))) and 1)); p++ }; return v }
        fun align() { p = (p + 7) and 7.inv() }
        fun eof() = (p shr 3) >= b.size
    }

    fun feedPes(p: ByteArray) {
        if (p.size < 12 || p[0].toInt() != 0 || p[1].toInt() != 0 || p[2].toInt() != 1) return
        var i = 9 + (p[8].toInt() and 0xFF) + 2
        while (i + 6 <= p.size && p[i].toInt() == 0x0F) {
            val t = p[i + 1].toInt() and 0xFF
            val page = ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF)
            val len = ((p[i + 4].toInt() and 0xFF) shl 8) or (p[i + 5].toInt() and 0xFF)
            val end = minOf(i + 6 + len, p.size)
            if (page == stream.comp || page == stream.anc || page == 0 || stream.comp == 0) segment(t, p, i + 6, end)
            i += 6 + len
        }
    }

    private fun u(a: ByteArray, i: Int) = a[i].toInt() and 0xFF

    private fun segment(t: Int, s: ByteArray, o: Int, end: Int) {
        try {
            when (t) {
                0x10 -> {
                    val l = ArrayList<IntArray>(); var j = o + 2
                    while (j + 6 <= end) { l.add(intArrayOf(u(s, j), (u(s, j + 2) shl 8) or u(s, j + 3), (u(s, j + 4) shl 8) or u(s, j + 5))); j += 6 }
                    pageRegs = l
                }
                0x11 -> {
                    val rid = u(s, o); val w = (u(s, o + 2) shl 8) or u(s, o + 3); val h = (u(s, o + 4) shl 8) or u(s, o + 5)
                    val cid = u(s, o + 7); val l = ArrayList<IntArray>(); var j = o + 10
                    while (j + 6 <= end) {
                        val oid = (u(s, j) shl 8) or u(s, j + 1)
                        val type = (u(s, j + 2) shr 4) and 3
                        l.add(intArrayOf(oid, ((u(s, j + 2) and 15) shl 8) or u(s, j + 3), ((u(s, j + 4) and 15) shl 8) or u(s, j + 5)))
                        j += 6; if (type == 1 || type == 2) j += 2
                    }
                    regions[rid] = Region(w, h, cid, l)
                }
                0x12 -> {
                    val cid = u(s, o); val tbl = IntArray(256); var j = o + 2
                    while (j + 4 <= end) {
                        val idx = u(s, j); val fl = u(s, j + 1); j += 2
                        val y: Int; val cr: Int; val cb: Int; val tr: Boolean
                        if (fl and 1 != 0) { y = u(s, j); cr = u(s, j + 1); cb = u(s, j + 2); tr = u(s, j + 3) >= 250; j += 4 }
                        else {
                            y = u(s, j) and 0xFC; cr = (((u(s, j) and 3) shl 2) or (u(s, j + 1) shr 6)) shl 4
                            cb = ((u(s, j + 1) shr 2) and 15) shl 4; tr = (u(s, j + 1) and 3) == 3; j += 2
                        }
                        tbl[idx] = if (y == 0 || tr) 0 else {
                            val yy = (y - 16).coerceAtLeast(0)
                            val r = (1.164 * yy + 1.596 * (cr - 128)).toInt().coerceIn(0, 255)
                            val g = (1.164 * yy - 0.813 * (cr - 128) - 0.391 * (cb - 128)).toInt().coerceIn(0, 255)
                            val b = (1.164 * yy + 2.018 * (cb - 128)).toInt().coerceIn(0, 255)
                            Color.rgb(r, g, b)
                        }
                    }
                    cluts[cid] = tbl
                }
                0x13 -> {
                    val oid = (u(s, o) shl 8) or u(s, o + 1)
                    if (((u(s, o + 2) shr 2) and 3) == 0) {
                        val tl = (u(s, o + 3) shl 8) or u(s, o + 4); val bl = (u(s, o + 5) shl 8) or u(s, o + 6)
                        val a = o + 7
                        if (a + tl + bl <= end) objs[oid] = s.copyOfRange(a, a + tl) to s.copyOfRange(a + tl, a + tl + bl)
                    }
                }
                0x80 -> render()
            }
        } catch (_: Exception) { /* segment abîmé : on ignore */ }
    }

    private fun render() {
        nDisplays++
        val rs = pageRegs.filter { regions.containsKey(it[0]) }
        if (rs.isEmpty()) { onDisplay(null); return }
        val W = rs.maxOf { it[1] + regions[it[0]]!!.w }.coerceIn(1, 2048)
        val H = rs.maxOf { it[2] + regions[it[0]]!!.h }.coerceIn(1, 2048)
        val px = IntArray(W * H)
        var x0 = W; var y0 = H; var x1 = -1; var y1 = -1
        for (r in rs) {
            val R = regions[r[0]]!!; val tbl = cluts[R.clut] ?: continue
            for (ob in R.objs) {
                val data = objs[ob[0]] ?: continue
                for (field in 0..1) {
                    val d = if (field == 0) data.first else data.second
                    var y = field; var j = 0
                    while (j < d.size) {
                        val dt = u(d, j); j++
                        when (dt) {
                            0x10, 0x11, 0x12 -> {
                                val row = IntArray(R.w); val b = Bits(d.copyOfRange(j, d.size))
                                when (dt) { 0x10 -> px2(b, row); 0x11 -> px4(b, row); else -> px8(b, row) }
                                b.align(); j += b.p shr 3
                                val yy = r[2] + ob[2] + y
                                for (xx in 0 until R.w) {
                                    val c = row[xx]; if (c == 0) continue
                                    val col = tbl[c and 255]; if (col == 0) continue
                                    val X = r[1] + ob[1] + xx
                                    if (X in 0 until W && yy in 0 until H) {
                                        px[yy * W + X] = col
                                        if (X < x0) x0 = X; if (X > x1) x1 = X; if (yy < y0) y0 = yy; if (yy > y1) y1 = yy
                                    }
                                }
                            }
                            0xF0 -> y += 2
                            0x20 -> j += 2
                            0x21 -> j += 4
                            0x22 -> j += 16
                        }
                    }
                }
            }
        }
        if (x1 < 0) { onDisplay(null); return }
        val pad = 14; val w = x1 - x0 + 1 + 2 * pad; val h = y1 - y0 + 1 + 2 * pad
        val out = IntArray(w * h) { Color.WHITE }
        for (y in y0..y1) for (x in x0..x1) {
            val c = px[y * W + x]; if (c == 0) continue
            val lum = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            val g = 255 - lum
            out[(y - y0 + pad) * w + (x - x0 + pad)] = Color.rgb(g, g, g)
        }
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        onDisplay(Bitmap.createScaledBitmap(bmp, w * 2, h * 2, true))
    }

    private fun px2(b: Bits, row: IntArray) {
        var x = 0
        while (!b.eof()) {
            var run: Int; var col: Int
            val c = b.get(2)
            if (c != 0) { run = 1; col = c } else if (b.get(1) == 1) { run = b.get(3) + 3; col = b.get(2) }
            else if (b.get(1) == 1) { run = 1; col = 0 }
            else when (b.get(2)) {
                0 -> return
                1 -> { run = 2; col = 0 }
                2 -> { run = b.get(4) + 12; col = b.get(2) }
                else -> { run = b.get(8) + 29; col = b.get(2) }
            }
            repeat(run) { if (x < row.size) row[x] = col; x++ }
        }
    }

    private fun px4(b: Bits, row: IntArray) {
        var x = 0
        while (!b.eof()) {
            var run: Int; var col: Int
            val c = b.get(4)
            if (c != 0) { run = 1; col = c }
            else if (b.get(1) == 0) { val r = b.get(3); if (r == 0) return; run = r + 2; col = 0 }
            else if (b.get(1) == 0) { run = b.get(2) + 4; col = b.get(4) }
            else when (b.get(2)) {
                0 -> { run = 1; col = 0 }
                1 -> { run = 2; col = 0 }
                2 -> { run = b.get(4) + 9; col = b.get(4) }
                else -> { run = b.get(8) + 25; col = b.get(4) }
            }
            repeat(run) { if (x < row.size) row[x] = col; x++ }
        }
    }

    private fun px8(b: Bits, row: IntArray) {
        var x = 0
        while (!b.eof()) {
            var run: Int; var col: Int
            val c = b.get(8)
            if (c != 0) { run = 1; col = c }
            else if (b.get(1) == 0) { val r = b.get(7); if (r == 0) return; run = r; col = 0 }
            else { run = b.get(7); col = b.get(8) }
            repeat(run) { if (x < row.size) row[x] = col; x++ }
        }
    }
}

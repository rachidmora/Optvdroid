package com.atvplayer

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atvplayer.data.ImageSettings
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

data class TrackItem(val id: Int, val name: String)

/**
 * Enveloppe libVLC : lecture TS, pistes audio, sous-titres, décalages,
 * et réglages d'image (saturation / contraste / luminosité) appliqués
 * en GPU via le Paint de la TextureView.
 */
class PlayerController(ctx: Context, initialImage: ImageSettings) {

    private val main = Handler(Looper.getMainLooper())
    private val libVlc = LibVLC(
        ctx,
        arrayListOf("--network-caching=1500", "--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames")
    )
    private val mp = MediaPlayer(libVlc)
    private var layout: VLCVideoLayout? = null
    private var texture: TextureView? = null

    var audioTracks by mutableStateOf<List<TrackItem>>(emptyList()); private set
    var subTracks by mutableStateOf<List<TrackItem>>(emptyList()); private set
    var selectedAudio by mutableStateOf(-1); private set
    var selectedSub by mutableStateOf(-1); private set
    var audioDelayMs by mutableStateOf(0); private set
    var subDelayMs by mutableStateOf(0); private set
    var image by mutableStateOf(initialImage); private set
    var buffering by mutableStateOf(true); private set
    var error by mutableStateOf(false); private set
    var fill by mutableStateOf(false); private set

    init {
        mp.setEventListener { ev ->
            main.post {
                when (ev.type) {
                    MediaPlayer.Event.Opening -> { buffering = true; error = false }
                    MediaPlayer.Event.Playing -> { buffering = false; refreshTracks() }
                    MediaPlayer.Event.Buffering -> { buffering = ev.buffering < 100f }
                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()
                    MediaPlayer.Event.Vout -> applyImage()
                    MediaPlayer.Event.EncounteredError -> { error = true; buffering = false }
                }
            }
        }
    }

    fun attach(view: VLCVideoLayout) {
        layout = view
        // TextureView obligatoire pour pouvoir appliquer le filtre couleur via setLayerPaint
        mp.attachViews(view, null, true, true)
        view.post { applyImage() }
    }

    fun play(url: String) {
        error = false; buffering = true
        audioTracks = emptyList(); subTracks = emptyList()
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoding(true)
            addOption(":network-caching=1500")
        }
        mp.media = media
        media.release()
        mp.play()
        main.postDelayed({ applyImage() }, 600)
    }

    private fun refreshTracks() {
        audioTracks = mp.audioTracks?.map { TrackItem(it.id, it.name) }.orEmpty()
        subTracks = mp.spuTracks?.map { TrackItem(it.id, it.name) }.orEmpty()
        selectedAudio = mp.audioTrack
        selectedSub = mp.spuTrack
    }

    fun selectAudio(id: Int) { mp.setAudioTrack(id); selectedAudio = id }
    fun selectSub(id: Int) { mp.setSpuTrack(id); selectedSub = id }

    fun setAudioDelay(ms: Int) { audioDelayMs = ms; mp.setAudioDelay(ms * 1000L) }
    fun setSubDelay(ms: Int) { subDelayMs = ms; mp.setSpuDelay(ms * 1000L) }

    /** Ajoute un fichier .srt externe. */
    fun addSubtitle(uri: Uri) {
        mp.addSlave(Media.Slave.Type.Subtitle, uri, true)
        main.postDelayed({ refreshTracks() }, 800)
    }

    fun toggleFill() {
        fill = !fill
        mp.videoScale = if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    // ---------- Réglages d'image ----------

    fun updateImage(s: ImageSettings) { image = s; applyImage() }

    private fun findTexture(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun applyImage() {
        val tv = texture ?: layout?.let { findTexture(it) }?.also { texture = it } ?: return
        val s = image
        if (s.isDefault) { tv.setLayerType(View.LAYER_TYPE_NONE, null); return }
        val sat = ColorMatrix().apply { setSaturation(s.saturation) }
        val c = s.contrast
        val t = (1f - c) * 128f + s.brightness * 255f
        val cb = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        sat.postConcat(cb)
        tv.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(sat) })
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        try { mp.stop(); mp.detachViews(); mp.release(); libVlc.release() } catch (_: Exception) {}
    }
}

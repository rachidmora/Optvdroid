package com.atvplayer

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
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
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.concurrent.thread

data class TrackItem(val id: Int, val name: String)

enum class Aspect(val label: String) { Fit("Ajusté"), Fill("Remplir"), R16x9("16:9"), R4x3("4:3") }
enum class Deint(val label: String) { Auto("Auto"), Off("Non"), On("Oui") }

/** Shader AGSL (Android 13+) : netteté + saturation + contraste + luminosité, en GPU. */
private const val AGSL = """
uniform shader content;
uniform half sat;
uniform half con;
uniform half bri;
uniform half sharp;
half4 main(float2 p) {
    half4 c = content.eval(p);
    if (sharp > 0.0) {
        half4 n = content.eval(p + float2(0.0, -1.0));
        half4 s = content.eval(p + float2(0.0, 1.0));
        half4 e = content.eval(p + float2(1.0, 0.0));
        half4 w = content.eval(p + float2(-1.0, 0.0));
        half4 blur = (n + s + e + w) * 0.25;
        c.rgb = c.rgb + (c.rgb - blur.rgb) * sharp;
    }
    half l = dot(c.rgb, half3(0.299, 0.587, 0.114));
    c.rgb = mix(half3(l), c.rgb, sat);
    c.rgb = (c.rgb - 0.5) * con + 0.5 + bri;
    return half4(clamp(c.rgb, 0.0, 1.0), c.a);
}
"""

/**
 * Enveloppe libVLC : lecture TS, pistes audio, sous-titres, audio/SRT externes,
 * décalages (synchro avec le direct) et réglages d'image / qualité.
 */
class PlayerController(private val appCtx: Context, initialImage: ImageSettings) {

    private val main = Handler(Looper.getMainLooper())
    private val libVlc = LibVLC(
        appCtx,
        arrayListOf("--network-caching=1500", "--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames")
    )
    private val mp = MediaPlayer(libVlc)
    private var layout: VLCVideoLayout? = null
    private var texture: TextureView? = null
    private var lastUrl: String? = null

    var audioTracks by mutableStateOf<List<TrackItem>>(emptyList()); private set
    var subTracks by mutableStateOf<List<TrackItem>>(emptyList()); private set
    var selectedAudio by mutableStateOf(-1); private set
    var selectedSub by mutableStateOf(-1); private set
    var audioDelayMs by mutableStateOf(0); private set
    var subDelayMs by mutableStateOf(0); private set
    var image by mutableStateOf(initialImage); private set
    var buffering by mutableStateOf(true); private set
    var error by mutableStateOf(false); private set
    var aspect by mutableStateOf(Aspect.Fit); private set
    var deint by mutableStateOf(Deint.Auto); private set
    var hardware by mutableStateOf(true); private set
    var message by mutableStateOf<String?>(null); private set

    val sharpnessSupported get() = Build.VERSION.SDK_INT >= 33

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
        // TextureView obligatoire pour pouvoir appliquer les effets GPU
        mp.attachViews(view, null, true, true)
        view.post { applyImage() }
    }

    fun play(url: String) {
        lastUrl = url
        error = false; buffering = true
        audioTracks = emptyList(); subTracks = emptyList()
        audioDelayMs = 0; subDelayMs = 0
        val media = Media(libVlc, Uri.parse(url)).apply {
            addOption(if (hardware) ":avcodec-hw=any" else ":avcodec-hw=none")
            addOption(":network-caching=1500")
            when (deint) {
                Deint.Off -> addOption(":deinterlace=0")
                Deint.On -> { addOption(":deinterlace=1"); addOption(":deinterlace-mode=yadif") }
                Deint.Auto -> {}
            }
        }
        mp.media = media
        media.release()
        mp.play()
        main.postDelayed({ applyImage() }, 600)
    }

    private fun replay() { lastUrl?.let { play(it) } }

    private fun refreshTracks() {
        audioTracks = mp.audioTracks?.map { TrackItem(it.id, it.name) }.orEmpty()
        subTracks = mp.spuTracks?.map { TrackItem(it.id, it.name) }.orEmpty()
        selectedAudio = mp.audioTrack
        selectedSub = mp.spuTrack
    }

    fun selectAudio(id: Int) { mp.setAudioTrack(id); selectedAudio = id }
    fun selectSub(id: Int) { mp.setSpuTrack(id); selectedSub = id }

    // ---------- Synchro (live) ----------
    fun setAudioDelay(ms: Int) { audioDelayMs = ms; mp.setAudioDelay(ms * 1000L) }
    fun setSubDelay(ms: Int) { subDelayMs = ms; mp.setSpuDelay(ms * 1000L) }

    // ---------- Sources externes ----------

    /** Ajoute un fichier (content://) ou une URL (http, rtsp...) comme piste externe. */
    private fun addSlave(type: Int, source: String, isFile: Boolean, label: String) {
        val uri = Uri.parse(source.trim())
        if (isFile && uri.scheme == "content") {
            thread {
                try {
                    val ext = if (type == IMedia.Slave.Type.Subtitle) ".srt" else ".audio"
                    val f = File(appCtx.cacheDir, "ext_${System.currentTimeMillis()}$ext")
                    appCtx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    main.post { doAddSlave(type, Uri.fromFile(f), label) }
                } catch (e: Exception) {
                    main.post { message = "Impossible de lire le fichier : ${e.message}" }
                }
            }
        } else doAddSlave(type, uri, label)
    }

    private fun doAddSlave(type: Int, uri: Uri, label: String) {
        val ok = mp.addSlave(type, uri, true)
        message = if (ok) "$label ajouté" else "$label : échec"
        main.postDelayed({ refreshTracks() }, 1000)
    }

    fun addExternalAudioUrl(url: String) = addSlave(IMedia.Slave.Type.Audio, url, false, "Audio externe")
    fun addExternalAudioFile(uri: Uri) = addSlave(IMedia.Slave.Type.Audio, uri.toString(), true, "Audio externe")
    fun addSubtitleUrl(url: String) = addSlave(IMedia.Slave.Type.Subtitle, url, false, "Sous-titre")
    fun addSubtitleFile(uri: Uri) = addSlave(IMedia.Slave.Type.Subtitle, uri.toString(), true, "Sous-titre")
    fun clearMessage() { message = null }

    // ---------- Qualité ----------
    fun setAspect(a: Aspect) {
        aspect = a
        when (a) {
            Aspect.Fit -> { mp.setAspectRatio(null); mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT }
            Aspect.Fill -> { mp.setAspectRatio(null); mp.videoScale = MediaPlayer.ScaleType.SURFACE_FILL }
            Aspect.R16x9 -> { mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT; mp.setAspectRatio("16:9") }
            Aspect.R4x3 -> { mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT; mp.setAspectRatio("4:3") }
        }
    }

    fun setDeint(d: Deint) { deint = d; replay() }
    fun setHardware(h: Boolean) { hardware = h; replay() }

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
        if (Build.VERSION.SDK_INT >= 33) {
            applyShader(tv, s)
        } else {
            applyPaint(tv, s)
        }
    }

    private fun applyShader(tv: TextureView, s: ImageSettings) {
        if (Build.VERSION.SDK_INT < 33) return
        if (s.isDefault) { tv.setRenderEffect(null); return }
        try {
            val sh = RuntimeShader(AGSL)
            sh.setFloatUniform("sat", s.saturation)
            sh.setFloatUniform("con", s.contrast)
            sh.setFloatUniform("bri", s.brightness)
            sh.setFloatUniform("sharp", s.sharpness)
            tv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"))
        } catch (e: Throwable) {
            applyPaint(tv, s)
        }
    }

    private fun applyPaint(tv: TextureView, s: ImageSettings) {
        if (s.saturation == 1f && s.contrast == 1f && s.brightness == 0f) {
            tv.setLayerType(View.LAYER_TYPE_NONE, null); return
        }
        val sat = ColorMatrix().apply { setSaturation(s.saturation) }
        val c = s.contrast
        val t = (1f - c) * 128f + s.brightness * 255f
        sat.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        tv.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(sat) })
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        try { mp.stop(); mp.detachViews(); mp.release(); libVlc.release() } catch (_: Exception) {}
    }
}

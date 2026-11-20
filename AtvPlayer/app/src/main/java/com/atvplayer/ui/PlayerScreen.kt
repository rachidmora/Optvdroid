package com.atvplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.atvplayer.MainViewModel
import com.atvplayer.PlayerController
import com.atvplayer.data.ImageSettings
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private enum class Panel { None, Audio, Subs, Image }

@Composable
fun PlayerScreen(vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val controller = remember { PlayerController(ctx.applicationContext, vm.prefs.loadImage()) }

    // Plein écran immersif + paysage pendant la lecture
    DisposableEffect(Unit) {
        val w = activity.window
        val ic = WindowCompat.getInsetsController(w, w.decorView)
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            ic.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            vm.prefs.saveImage(controller.image)
            controller.release()
        }
    }

    val ch = vm.current
    LaunchedEffect(ch?.ref) { ch?.let { controller.play(vm.api.streamUrl(it)) } }

    var showBar by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.None) }
    var barTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(barTick, panel) {
        if (panel == Panel.None) { delay(5000); showBar = false }
    }
    fun wake() { showBar = true; barTick++ }
    LaunchedEffect(ch?.ref) { wake() }

    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) { delay(5_000); value = System.currentTimeMillis() / 1000 }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (panel != Panel.None) panel = Panel.None
                    else if (showBar) showBar = false else wake()
                }
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = {
                        if (acc < -120f) vm.zap(+1) else if (acc > 120f) vm.zap(-1)
                    },
                ) { _, dy -> acc += dy }
            },
    ) {
        AndroidView(
            factory = { c -> VLCVideoLayout(c).also { controller.attach(it) } },
            modifier = Modifier.fillMaxSize(),
        )

        if (controller.buffering && !controller.error) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pal.Accent)
        }
        if (controller.error) {
            Text(
                "Impossible de lire ce flux", color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(Pal.Glass).padding(16.dp),
            )
        }

        // Bouton retour
        AnimatedVisibility(showBar, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = onClose, modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(Pal.Glass)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
            }
        }

        // Infobar moderne
        AnimatedVisibility(
            showBar && panel == Panel.None,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            if (ch != null) InfoBar(vm, ch, nowSec, controller,
                onPanel = { panel = it },
                onPrev = { vm.zap(-1) }, onNext = { vm.zap(+1) },
            )
        }

        // Panneau latéral (audio / sous-titres / image)
        AnimatedVisibility(
            panel != Panel.None, Modifier.align(Alignment.CenterEnd),
            enter = slideInVertically { 0 } + fadeIn(), exit = fadeOut(),
        ) {
            SidePanel(panel, controller, onClose = { panel = Panel.None })
        }
    }
}

@Composable
private fun InfoBar(
    vm: MainViewModel,
    ch: com.atvplayer.data.Channel,
    nowSec: Long,
    controller: PlayerController,
    onPanel: (Panel) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080C16))))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .padding(top = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picon(vm.api, ch, Modifier.size(width = 96.dp, height = 60.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${ch.number}  ${ch.name}", color = Pal.Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (now != null) {
                    Text(now.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(now.begin), color = Pal.Muted, fontSize = 12.sp)
                        LinearProgressIndicator(
                            progress = { now.progress(nowSec) },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Pal.Accent, trackColor = Color(0x33FFFFFF),
                        )
                        Text("${fmtTime(now.end)}  •  ${now.remainingMin(nowSec)} min", color = Pal.Muted, fontSize = 12.sp)
                    }
                } else {
                    Text("Pas d'informations EPG", color = Pal.Muted)
                }
            }
            IconButton(onPrev) { Icon(Icons.Default.KeyboardArrowDown, "Précédente", tint = Color.White) }
            IconButton(onNext) { Icon(Icons.Default.KeyboardArrowUp, "Suivante", tint = Color.White) }
        }
        if (now != null && now.description.isNotBlank()) {
            Text(now.description, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (next != null) {
            Text("Ensuite  ${fmtTime(next.begin)}  ${next.title}", color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Audio", Icons.Default.Audiotrack) { onPanel(Panel.Audio) }
            ActionChip("Sous-titres", Icons.Default.Subtitles) { onPanel(Panel.Subs) }
            ActionChip("Image", Icons.Default.Tune) { onPanel(Panel.Image) }
            ActionChip(if (controller.fill) "Ajusté" else "Remplir", Icons.Default.AspectRatio) { controller.toggleFill() }
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun SidePanel(panel: Panel, c: PlayerController, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(340.dp).background(Pal.Glass)
            .pointerInput(Unit) { detectTapGestures { } } // ne ferme pas au tap dans le panneau
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (panel) { Panel.Audio -> "Audio"; Panel.Subs -> "Sous-titres"; else -> "Image" },
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            IconButton(onClose) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
        }
        when (panel) {
            Panel.Audio -> {
                TrackList(c.audioTracks, c.selectedAudio, "Aucune piste audio") { c.selectAudio(it) }
                DelaySlider("Décalage audio", c.audioDelayMs) { c.setAudioDelay(it) }
            }
            Panel.Subs -> {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { c.addSubtitle(it) }
                }
                TrackList(c.subTracks, c.selectedSub, "Aucun sous-titre") { c.selectSub(it) }
                DelaySlider("Décalage sous-titres", c.subDelayMs) { c.setSubDelay(it) }
                TextButton({ picker.launch(arrayOf("*/*")) }) { Text("Charger un fichier .srt") }
            }
            Panel.Image -> ImageControls(c)
            Panel.None -> {}
        }
    }
}

@Composable
private fun ColumnScope.TrackList(tracks: List<com.atvplayer.TrackItem>, selected: Int, empty: String, onPick: (Int) -> Unit) {
    if (tracks.isEmpty()) Text(empty, color = Pal.Muted, modifier = Modifier.padding(vertical = 12.dp))
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(tracks, key = { it.id }) { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(t.id) }.padding(vertical = 10.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = t.id == selected, onClick = { onPick(t.id) })
                Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun DelaySlider(label: String, valueMs: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text("$label : ${valueMs} ms", color = Pal.Muted, fontSize = 13.sp)
        Slider(valueMs.toFloat(), { onChange((it / 50).toInt() * 50) }, valueRange = -3000f..3000f)
    }
}

@Composable
private fun ImageControls(c: PlayerController) {
    val s = c.image
    @Composable
    fun row(label: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
        Column(Modifier.padding(top = 14.dp)) {
            Text("$label : ${"%.2f".format(value)}", color = Color.White, fontSize = 14.sp)
            Slider(value, set, valueRange = range)
        }
    }
    row("Saturation", s.saturation, 0f..2f) { c.updateImage(s.copy(saturation = it)) }
    row("Contraste", s.contrast, 0.5f..1.5f) { c.updateImage(s.copy(contrast = it)) }
    row("Luminosité", s.brightness, -0.5f..0.5f) { c.updateImage(s.copy(brightness = it)) }
    TextButton({ c.updateImage(ImageSettings()) }, Modifier.padding(top = 8.dp)) { Text("Réinitialiser") }
}

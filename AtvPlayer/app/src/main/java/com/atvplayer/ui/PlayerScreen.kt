package com.atvplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
import com.atvplayer.SubtitleEngine
import com.atvplayer.data.Channel
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

enum class Panel { None, Audio, Subs, Image, Quality }

@Composable
fun PlayerScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val controller = remember { PlayerController(ctx.applicationContext, vm.prefs) }
    val subs = remember { SubtitleEngine(ctx.applicationContext, vm.prefs) }

    // Plein écran immersif + paysage (comme une TV)
    DisposableEffect(Unit) {
        val w = activity.window
        val ic = WindowCompat.getInsetsController(w, w.decorView)
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // l'écran ne s'éteint plus pendant la lecture
        onDispose {
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ic.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            vm.prefs.saveImage(controller.image)
            controller.release()
        }
    }

    val ch = vm.current
    LaunchedEffect(ch?.ref, vm.api) { ch?.let { controller.play(vm.api.streamUrl(it)) } }
    LaunchedEffect(ch?.ref) { subs.clear() }
    var panel by remember { mutableStateOf(Panel.None) }
    var showBar by remember { mutableStateOf(false) }
    var barTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(barTick, showBar) { if (showBar) { delay(5000); showBar = false } }
    LaunchedEffect(ch?.ref) { if (!vm.showList) { showBar = true; barTick++ } }

    // Messages (audio/SRT externes)
    controller.message?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            controller.clearMessage()
        }
    }

    fun toggle(p: Panel) { panel = if (panel == p) Panel.None else p; if (panel != Panel.None) showBar = false }

    // Touches de télécommande : couleurs, CH+/-, OK
    DisposableEffect(Unit) {
        vm.keyHandler = handler@{ code ->
            when (code) {
                KeyEvent.KEYCODE_PROG_RED -> { toggle(Panel.Audio); true }
                KeyEvent.KEYCODE_PROG_GREEN -> { toggle(Panel.Subs); true }
                KeyEvent.KEYCODE_PROG_YELLOW -> { toggle(Panel.Image); true }
                KeyEvent.KEYCODE_PROG_BLUE -> { toggle(Panel.Quality); true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(+1); true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); true }
                KeyEvent.KEYCODE_DPAD_UP -> if (!vm.showList && panel == Panel.None) { vm.zap(+1); true } else false
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!vm.showList && panel == Panel.None) { vm.zap(-1); true } else false
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (!vm.showList && panel == Panel.None) { vm.showList = true; true } else false
                else -> false
            }
        }
        onDispose { vm.keyHandler = null }
    }

    BackHandler(enabled = panel != Panel.None || vm.showList) {
        if (panel != Panel.None) panel = Panel.None else vm.showList = false
    }

    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) { delay(5_000); value = System.currentTimeMillis() / 1000 }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    when {
                        panel != Panel.None -> panel = Panel.None
                        vm.showList -> { if (vm.current != null) vm.showList = false }
                        else -> { showBar = !showBar; barTick++ }
                    }
                }
            }
            .pointerInput(Unit) {
                var accX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accX = 0f },
                    onDragEnd = { if (!vm.showList && panel == Panel.None && accX > 120f) vm.showList = true },
                ) { _, dx -> accX += dx }
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = {
                        if (!vm.showList) { if (acc < -120f) vm.zap(+1) else if (acc > 120f) vm.zap(-1) }
                    },
                ) { _, dy -> acc += dy }
            },
    ) {
        AndroidView(
            factory = { c -> VLCVideoLayout(c).also { controller.attach(it) } },
            modifier = Modifier.fillMaxSize(),
        )

        // Comme sur un vrai récepteur : écran noir pendant le changement de chaîne (pas de cercle qui tourne),
        // puis fondu vers l'image. Si rien n'arrive : « PAS DE SIGNAL ».
        val cover by animateFloatAsState(if (controller.videoReady) 0f else 1f, tween(if (controller.videoReady) 350 else 80), label = "cover")
        if (cover > 0.01f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = cover)), contentAlignment = Alignment.Center) {
                if (controller.noSignal) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PAS DE SIGNAL", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                        Text(ch?.name ?: "", color = Pal.Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(controller.lastEvent, color = Color(0x55FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }

        // Sous-titres SRT (style OSN)
        SubtitleOverlay(subs)
        LiveSubsOverlay(controller.live, subs)

        // Infobar moderne (liste fermée)
        AnimatedVisibility(
            showBar && !vm.showList && panel == Panel.None,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            if (ch != null) InfoBar(vm, ch, nowSec, controller.audioTracks.size, controller.subTracks.count { it.id >= 0 },
                onRed = { toggle(Panel.Audio) }, onGreen = { toggle(Panel.Subs) },
                onYellow = { toggle(Panel.Image) }, onBlue = { toggle(Panel.Quality) },
                onList = { vm.showList = true },
            )
        }

        // Liste des chaînes : moitié gauche de l'écran, par-dessus la vidéo
        AnimatedVisibility(
            vm.showList,
            Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelPanel(
                vm, nowSec, onOpenSettings,
                onRed = { toggle(Panel.Audio) }, onGreen = { toggle(Panel.Subs) },
                onYellow = { toggle(Panel.Image) }, onBlue = { toggle(Panel.Quality) },
            )
        }

        // Fiche du programme en cours, à droite de la liste
        AnimatedVisibility(
            vm.showList && panel == Panel.None, Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(), exit = fadeOut(),
        ) { ch?.let { DetailsCard(vm, it, nowSec) } }

        // Panneaux de réglage (à droite)
        AnimatedVisibility(
            panel != Panel.None, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            SidePanel(panel, controller, subs, vm.current?.let { vm.now[it.key]?.begin }, onClose = { panel = Panel.None })
        }
    }
}

@Composable
private fun DetailsCard(vm: MainViewModel, ch: Channel, nowSec: Long) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    val poster = now?.title?.let { vm.posters[it] }
    LaunchedEffect(now?.title) { now?.title?.let { vm.ensurePoster(it) } }
    Column(
        Modifier.padding(end = 24.dp).fillMaxWidth(0.42f).clip(RoundedCornerShape(24.dp)).background(Pal.Panel).padding(18.dp),
    ) {
        Row {
            if (poster != null) {
                AsyncImage(
                    model = poster, contentDescription = now?.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 92.dp, height = 136.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${ch.number} · ${ch.name}".uppercase(), color = Pal.Accent2.copy(alpha = 1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
                if (now != null) {
                    Text(now.title, color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text("${fmtTime(now.begin)} – ${fmtTime(now.end)} · ${now.remainingMin(nowSec)} min restantes", color = Pal.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    if (now.description.isNotBlank()) Text(now.description, color = Color(0xFFB7C0D8), fontSize = 13.sp, maxLines = 4,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                } else Text("Pas d'informations EPG", color = Pal.Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (next != null) {
            Row(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("ENSUITE", color = Pal.Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Spacer(Modifier.width(10.dp))
                Text("${fmtTime(next.begin)}  ${next.title}", color = Pal.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InfoBar(
    vm: MainViewModel,
    ch: Channel,
    nowSec: Long,
    audioCount: Int,
    subCount: Int,
    onRed: () -> Unit, onGreen: () -> Unit, onYellow: () -> Unit, onBlue: () -> Unit,
    onList: () -> Unit,
) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    val poster = now?.title?.let { vm.posters[it] }
    LaunchedEffect(now?.title) { now?.title?.let { vm.ensurePoster(it) } }
    val shape = RoundedCornerShape(26.dp)

    Column(
        Modifier.navigationBarsPadding().padding(horizontal = 30.dp, vertical = 22.dp)
            .fillMaxWidth().clip(shape).background(Pal.Glass).border(1.dp, Color(0x1AFFFFFF), shape)
            .padding(horizontal = 26.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (poster != null) {
                AsyncImage(
                    model = poster, contentDescription = now?.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 112.dp, height = 168.dp).clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)),
                )
            } else {
                Picon(vm.api, ch, Modifier.size(width = 132.dp, height = 84.dp))
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(Pal.AccentBrush).padding(horizontal = 11.dp, vertical = 3.dp)) {
                        Text("${ch.number}", color = Color(0xFF04121A), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    if (poster != null) { Picon(vm.api, ch, Modifier.size(width = 58.dp, height = 36.dp)); Spacer(Modifier.width(10.dp)) }
                    Text(ch.name, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        qualityOf(ch.name)?.let { Badge(it, true) }
                        if (audioCount > 1) Badge("AUDIO ×$audioCount")
                        if (subCount > 0) Badge("SUB")
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(fmtTime(nowSec), color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                if (now != null) {
                    Text(now.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(now.begin), color = Color(0xFFC9D2E8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(
                            progress = { now.progress(nowSec) },
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Pal.Accent, trackColor = Color(0x26FFFFFF),
                        )
                        Text("${fmtTime(now.end)}   ", color = Color(0xFFC9D2E8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${now.remainingMin(nowSec)} min restantes", color = Pal.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (now.description.isNotBlank()) {
                        Text(now.description, color = Color(0xFFB7C0D8), fontSize = 14.sp, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    Text("Pas d'informations EPG", color = Pal.Muted, fontSize = 18.sp)
                }
                if (next != null) {
                    Row(
                        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ENSUITE", color = Pal.Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("${fmtTime(next.begin)}  ${next.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(1.dp).background(Pal.Divider))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorKeys(onRed, onGreen, onYellow, onBlue)
            Spacer(Modifier.weight(1f))
            if (vm.posterError != null) Text(vm.posterError ?: "", color = Pal.Muted, fontSize = 12.sp)
            Box(Modifier.clickable(onClick = onList).padding(8.dp)) {
                Text("☰  Chaînes", color = Pal.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

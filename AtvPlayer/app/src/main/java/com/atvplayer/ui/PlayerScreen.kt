package com.atvplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.atvplayer.data.Channel
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

enum class Panel { None, Audio, Subs, Image, Quality }

@Composable
fun PlayerScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val controller = remember { PlayerController(ctx.applicationContext, vm.prefs) }

    // Plein écran immersif + paysage (comme une TV)
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
    LaunchedEffect(ch?.ref, vm.api) { ch?.let { controller.play(vm.api.streamUrl(it)) } }

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

        // Infobar moderne (liste fermée)
        AnimatedVisibility(
            showBar && !vm.showList && panel == Panel.None,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            if (ch != null) InfoBar(vm, ch, nowSec,
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

        // Panneaux de réglage (à droite)
        AnimatedVisibility(
            panel != Panel.None, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            SidePanel(panel, controller, onClose = { panel = Panel.None })
        }
    }
}

@Composable
private fun InfoBar(
    vm: MainViewModel,
    ch: Channel,
    nowSec: Long,
    onRed: () -> Unit, onGreen: () -> Unit, onYellow: () -> Unit, onBlue: () -> Unit,
    onList: () -> Unit,
) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080C16))))
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .padding(top = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picon(vm.api, ch, Modifier.size(width = 96.dp, height = 60.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${ch.number}  ${ch.name}", color = Pal.Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (now != null) {
                    Text(now.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        }
        if (now != null && now.description.isNotBlank()) {
            Text(now.description, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (next != null) {
            Text("Ensuite  ${fmtTime(next.begin)}  ${next.title}", color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))
                    .clickable(onClick = onList).padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text("☰  Chaînes", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(12.dp))
            ColorKeys(onRed, onGreen, onYellow, onBlue)
        }
    }
}

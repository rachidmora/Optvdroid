package com.atvplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atvplayer.Aspect
import com.atvplayer.Deint
import com.atvplayer.MainViewModel
import com.atvplayer.PlayerController
import com.atvplayer.TrackItem
import com.atvplayer.data.Channel
import com.atvplayer.data.ImageSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

// ====================== Liste de chaînes (moitié gauche) ======================

@Composable
fun ChannelPanel(
    vm: MainViewModel,
    nowSec: Long,
    onOpenSettings: () -> Unit,
    onRed: () -> Unit, onGreen: () -> Unit, onYellow: () -> Unit, onBlue: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val list = remember(vm.channels, query) {
        if (query.isBlank()) vm.channels else vm.channels.filter { it.name.contains(query, true) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(vm.channels, vm.current?.ref) {
        if (query.isBlank()) {
            val i = list.indexOfFirst { it.ref == vm.current?.ref }
            if (i >= 0) state.scrollToItem((i - 2).coerceAtLeast(0))
        }
    }
    val clock by produceState(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) {
        while (true) { delay(10_000); value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    }

    Column(
        Modifier.fillMaxHeight().fillMaxWidth(0.5f)
            .background(Brush.horizontalGradient(listOf(Color(0xF20B0F1A), Color(0xE60B0F1A), Color(0x990B0F1A))))
            .statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock, color = Pal.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(vm.selected?.name ?: "Chaînes", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Rechercher", tint = Pal.Muted) }
            IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualiser", tint = Pal.Muted) }
            IconButton(onOpenSettings) { Icon(Icons.Default.Settings, "Réglages", tint = Pal.Muted) }
        }
        if (searching) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Rechercher une chaîne") },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.bouquets) { b ->
                FilterChip(
                    selected = b.ref == vm.selected?.ref,
                    onClick = { vm.selectBouquet(b) },
                    label = { Text(b.name, maxLines = 1) },
                )
            }
        }
        vm.error?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.ref + it.number }) { ch -> ChannelRow(vm, ch, nowSec) }
        }
        ColorKeys(onRed, onGreen, onYellow, onBlue, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ChannelRow(vm: MainViewModel, ch: Channel, nowSec: Long) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    val playing = ch.ref == vm.current?.ref
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (playing) Pal.SurfaceHi else Color(0xAA141B2D))
            .then(if (playing) Modifier.border(BorderStroke(1.5.dp, Pal.Accent), RoundedCornerShape(14.dp)) else Modifier)
            .clickable { if (playing) vm.showList = false else vm.play(ch) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ch.number}", color = if (playing) Pal.Accent else Pal.Muted, fontSize = 13.sp, modifier = Modifier.width(32.dp))
        Picon(vm.api, ch, Modifier.size(width = 72.dp, height = 46.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = Pal.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Text("${fmtTime(now.begin)}  ${now.title}", color = Pal.Text.copy(alpha = .85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { now.progress(nowSec) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Pal.Accent, trackColor = Pal.SurfaceHi,
                )
                if (next != null) Text("${fmtTime(next.begin)}  ${next.title}", color = Pal.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Pas d'EPG", color = Pal.Muted, fontSize = 12.sp)
            }
        }
    }
}

// ====================== Panneaux de réglage (à droite) ======================

@Composable
fun SidePanel(panel: Panel, c: PlayerController, onClose: () -> Unit) {
    val accent = when (panel) {
        Panel.Audio -> KeyRed; Panel.Subs -> KeyGreen; Panel.Image -> KeyYellow; else -> KeyBlue
    }
    val title = when (panel) {
        Panel.Audio -> "Audio"; Panel.Subs -> "Sous-titres"; Panel.Image -> "Image"; else -> "Qualité"
    }
    Column(
        Modifier.fillMaxHeight().width(380.dp).background(Pal.Glass)
            .statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(accent))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClose) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
        }
        when (panel) {
            Panel.Audio -> AudioPanel(c)
            Panel.Subs -> SubsPanel(c)
            Panel.Image -> ImageControls(c)
            Panel.Quality -> QualityPanel(c)
            Panel.None -> {}
        }
    }
}

@Composable
private fun AudioPanel(c: PlayerController) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.addExternalAudioFile(it) } }
    TrackList(c.audioTracks, c.selectedAudio, "Aucune piste audio") { c.selectAudio(it) }
    SectionTitle("Audio externe (IP)")
    UrlField("URL du flux audio (http://…)", "Ajouter") { c.addExternalAudioUrl(it) }
    OutlinedButton({ picker.launch(arrayOf("audio/*", "*/*")) }, Modifier.padding(top = 6.dp)) { Text("Choisir un fichier audio") }
    SectionTitle("Synchro audio")
    DelayControl(c.audioDelayMs) { c.setAudioDelay(it) }
    Hint("Valeur + : le son arrive plus tard. Valeur − : plus tôt.")
}

@Composable
private fun SubsPanel(c: PlayerController) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.addSubtitleFile(it) } }
    TrackList(c.subTracks, c.selectedSub, "Aucun sous-titre") { c.selectSub(it) }
    SectionTitle("Sous-titres externes (.srt)")
    UrlField("URL du fichier .srt (http://…)", "Ajouter") { c.addSubtitleUrl(it) }
    OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.padding(top = 6.dp)) { Text("Choisir un fichier .srt") }
    SectionTitle("Synchro sous-titres")
    DelayControl(c.subDelayMs) { c.setSubDelay(it) }
    Hint("Valeur + : les sous-titres apparaissent plus tard. Valeur − : plus tôt.")
}

@Composable
private fun ImageControls(c: PlayerController) {
    val s = c.image
    LabeledSlider("Saturation", s.saturation, 0f..2f) { c.updateImage(s.copy(saturation = it)) }
    LabeledSlider("Contraste", s.contrast, 0.5f..1.5f) { c.updateImage(s.copy(contrast = it)) }
    LabeledSlider("Luminosité", s.brightness, -0.5f..0.5f) { c.updateImage(s.copy(brightness = it)) }
    TextButton({ c.updateImage(ImageSettings(sharpness = s.sharpness)) }, Modifier.padding(top = 8.dp)) { Text("Réinitialiser") }
}

@Composable
private fun QualityPanel(c: PlayerController) {
    SectionTitle("Format d'image")
    ChipRow(Aspect.values().toList(), c.aspect, { it.label }) { c.changeAspect(it) }

    SectionTitle("Netteté (texte, logos)")
    if (c.sharpnessSupported) {
        LabeledSlider("Netteté", c.image.sharpness, 0f..2f) { c.updateImage(c.image.copy(sharpness = it)) }
    } else {
        Hint("Nécessite Android 13 ou plus.")
    }

    SectionTitle("Désentrelacement")
    ChipRow(Deint.values().toList(), c.deint, { it.label }) { c.changeDeint(it) }

    SectionTitle("Décodage")
    ChipRow(listOf(true, false), c.hardware, { if (it) "Matériel" else "Logiciel" }) { c.changeHardware(it) }
    Hint("Désentrelacement et décodage relancent la chaîne. Si l'image est floue, la source (flux du récepteur) est peut-être en basse résolution.")
}

// ====================== Composants ======================

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = Pal.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Hint(t: String) {
    Text(t, color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun TrackList(tracks: List<TrackItem>, selected: Int, empty: String, onPick: (Int) -> Unit) {
    if (tracks.isEmpty()) Text(empty, color = Pal.Muted, modifier = Modifier.padding(vertical = 12.dp))
    tracks.forEach { t ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(t.id) }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = t.id == selected, onClick = { onPick(t.id) })
            Text(t.name, color = Color.White, fontSize = 14.sp, maxLines = 2)
        }
    }
}

@Composable
private fun UrlField(hint: String, button: String, onSubmit: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    OutlinedTextField(
        url, { url = it }, singleLine = true, placeholder = { Text(hint, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button({ if (url.isNotBlank()) { onSubmit(url); url = "" } }, Modifier.padding(top = 6.dp)) { Text(button) }
}

/** Réglage fin du délai (synchro avec le direct). */
@Composable
private fun DelayControl(valueMs: Int, onChange: (Int) -> Unit) {
    val txt = "%+.1f s".format(valueMs / 1000f)
    Text(txt, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    @Composable
    fun row(vararg steps: Int) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { st ->
                val label = (if (st > 0) "+" else "−") + (if (kotlin.math.abs(st) >= 1000) "${kotlin.math.abs(st) / 1000} s" else "0.${kotlin.math.abs(st) / 100} s")
                FilledTonalButton({ onChange(valueMs + st) }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 13.sp) }
            }
        }
    }
    row(-10000, -1000, -100)
    row(100, 1000, 10000)
    TextButton({ onChange(0) }) { Text("Remettre à 0") }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text("$label : ${"%.2f".format(value)}", color = Color.White, fontSize = 14.sp)
        Slider(value, set, valueRange = range)
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { it ->
            FilterChip(selected = it == selected, onClick = { onPick(it) }, label = { Text(label(it), fontSize = 13.sp) })
        }
    }
}

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import com.atvplayer.SubtitleEngine
import com.atvplayer.SubStyle
import com.atvplayer.Enc
import com.atvplayer.TrackItem
import com.atvplayer.data.Channel
import com.atvplayer.data.ImageSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
            .background(Pal.Panel)
            .statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(vm.selected?.name ?: "Chaînes", color = Pal.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${list.size} chaînes", color = Pal.Muted, fontSize = 12.sp)
            }
            IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Rechercher", tint = Pal.Muted) }
            IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualiser", tint = Pal.Muted) }
            IconButton(onOpenSettings) { Icon(Icons.Default.Settings, "Réglages", tint = Pal.Muted) }
            Spacer(Modifier.width(4.dp))
            Text(clock, color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (searching) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Rechercher une chaîne") },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.categories) { b ->
                val sel = b.ref == vm.selected?.ref
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) Pal.AccentBrush else Brush.horizontalGradient(listOf(Color(0x14FFFFFF), Color(0x14FFFFFF))))
                        .clickable { vm.selectBouquet(b) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(b.name, maxLines = 1, color = if (sel) Color(0xFF04121A) else Pal.Muted,
                        fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold)
                }
            }
        }
        vm.error?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pal.Accent)

        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(list, key = { it.ref + it.number }) { ch -> ChannelRow(vm, ch, nowSec) }
        }
        ColorKeys(onRed, onGreen, onYellow, onBlue)
    }
}

@Composable
private fun ChannelRow(vm: MainViewModel, ch: Channel, nowSec: Long) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    val playing = ch.ref == vm.current?.ref
    val fav = vm.isFavorite(ch)
    val shape = RoundedCornerShape(18.dp)
    LaunchedEffect(ch.key, vm.selected?.ref) { vm.ensureEpg(ch) }
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (playing) Brush.horizontalGradient(listOf(Color(0x2E22D3EE), Color(0x1F6366F1))) else Brush.horizontalGradient(listOf(Color(0x0DFFFFFF), Color(0x0DFFFFFF))))
            .then(if (playing) Modifier.border(BorderStroke(1.5.dp, Pal.Accent), shape) else Modifier)
            .clickable { if (playing) vm.showList = false else vm.play(ch) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ch.number}", color = if (playing) Pal.Accent else Pal.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Picon(vm.api, ch, Modifier.size(width = 84.dp, height = 54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                qualityOf(ch.name)?.let { Spacer(Modifier.width(8.dp)); Badge(it, it == "4K") }
                if (playing) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Pal.Live).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text("LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                    }
                }
            }
            if (now != null) {
                Text(now.title, color = Color(0xFFD3DBEE), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { now.progress(nowSec) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Pal.Accent, trackColor = Color(0x1FFFFFFF),
                )
                if (next != null) Text("${fmtTime(next.begin)}  ${next.title}", color = Pal.Muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            } else {
                Text("Pas d'EPG", color = Pal.Muted, fontSize = 12.sp)
            }
        }
        IconButton({ vm.toggleFavorite(ch) }) {
            Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder, "Favori", tint = if (fav) Pal.Amber else Pal.Muted)
        }
    }
}

// ====================== Panneaux de réglage (à droite) ======================

@Composable
fun SidePanel(panel: Panel, c: PlayerController, subs: SubtitleEngine, eventBegin: Long?, onClose: () -> Unit) {
    val accent = when (panel) {
        Panel.Audio -> KeyRed; Panel.Subs -> KeyGreen; Panel.Image -> KeyYellow; else -> KeyBlue
    }
    val title = when (panel) {
        Panel.Audio -> "Audio"; Panel.Subs -> "Sous-titres"; Panel.Image -> "Image"; else -> "Qualité"
    }
    Column(
        Modifier.padding(14.dp).fillMaxHeight().width(420.dp).clip(RoundedCornerShape(26.dp)).background(Pal.Glass)
            .statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 6.dp, height = 30.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Spacer(Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            IconButton(onClose) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
        }
        when (panel) {
            Panel.Audio -> AudioPanel(c)
            Panel.Subs -> SubsPanel(c, subs, eventBegin)
            Panel.Image -> ImageControls(c)
            Panel.Quality -> QualityPanel(c)
            Panel.None -> {}
        }
    }
}

@Composable
private fun AudioPanel(c: PlayerController) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.addExternalAudioFile(it) } }
    SectionTitle("Langue audio préférée (toutes les chaînes)")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("" to "Original", "ar" to "العربية", "en" to "English", "fr" to "Français", "de" to "Deutsch", "es" to "Español", "tr" to "Türkçe").forEach { (code, name) ->
            FilterChip(selected = c.audioLang == code, onClick = { c.changeAudioLang(code) }, label = { Text(name, fontSize = 13.sp) })
        }
    }
    Hint("Sur chaque chaîne, cette langue est choisie automatiquement si elle existe. Sinon la piste d'origine reste active.")
    SectionTitle("Pistes de la chaîne")
    TrackList(c.audioTracks, c.selectedAudio, "Aucune piste audio") { c.selectAudio(it) }

    SectionTitle("Sources audio IP")
    if (c.audioSources.isEmpty()) Hint("Aucune source enregistrée. Ajoute-en ci-dessous.")
    c.audioSources.forEach { src ->
        val active = c.activeAudio == src
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { c.playAudioSource(src) }.padding(vertical = 2.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = active, onClick = { c.playAudioSource(src) })
            Text(src.name, color = if (active) KeyRed else Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton({ c.removeSource(src) }) { Icon(Icons.Default.Close, "Supprimer", tint = Pal.Muted) }
        }
    }
    if (c.activeAudio != null) {
        Text("État : ${c.extAudioStatus}", color = if (c.extAudioStatus.startsWith("Erreur")) MaterialTheme.colorScheme.error else KeyGreen,
            fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton({ c.useChannelAudio() }, Modifier.padding(top = 4.dp)) { Text("Revenir à l'audio de la chaîne") }
    }
    Hint("Quand une source est active, le son de la chaîne est coupé. Règle ensuite la synchro ci-dessous.")

    SectionTitle("Synchro audio")
    DelayControl(c.audioDelayMs) { c.setAudioDelay(it) }
    Hint("Valeur + : le son IP est retardé. Si le commentaire est en AVANCE sur l'image, retarde plutôt la vidéo :")
    LabeledSlider("Retard vidéo (s)", c.videoDelayMs / 1000f, 0f..15f,
        onFinished = { c.changeVideoDelay(c.videoDelayMs) }) { c.previewVideoDelay((it * 2).roundToInt() * 500) }
    Hint("Relâche le curseur pour appliquer (la chaîne se relance avec un tampon plus long).")

    SectionTitle("Ajouter une source")
    AddSource(c)
    SectionTitle("Ajouter une série (ex. 1 à 10)")
    AddSeries(c)

    SectionTitle("Autres")
    UrlField("URL audio à jouer une fois (http://…)", "Jouer") { c.addExternalAudioUrl(it) }
    OutlinedButton({ picker.launch(arrayOf("audio/*", "*/*")) }, Modifier.padding(top = 6.dp)) { Text("Choisir un fichier audio") }
}

@Composable
private fun AddSource(c: PlayerController) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nom") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("URL du flux audio") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    Button({ c.addSource(name, url); name = ""; url = "" }, Modifier.padding(top = 6.dp)) { Text("Enregistrer") }
}

@Composable
private fun AddSeries(c: PlayerController) {
    var urlP by remember { mutableStateOf("") }
    var nameP by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("10") }
    OutlinedTextField(urlP, { urlP = it }, singleLine = true, label = { Text("URL avec {n}  (ex. http://…/{n})") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(nameP, { nameP = it }, singleLine = true, label = { Text("Nom avec {n}  (ex. Match {n})") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(from, { from = it }, singleLine = true, label = { Text("De") }, modifier = Modifier.weight(1f))
        OutlinedTextField(to, { to = it }, singleLine = true, label = { Text("À") }, modifier = Modifier.weight(1f))
    }
    Button({ c.addSeries(urlP, nameP, from.toIntOrNull() ?: 1, to.toIntOrNull() ?: 10) }, Modifier.padding(top = 6.dp)) { Text("Ajouter la série") }
}

@Composable
private fun SubsPanel(c: PlayerController, subs: SubtitleEngine, eventBegin: Long?) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { subs.loadUri(it, eventBegin) } }

    // --- Traduction en direct du télétexte (DeepL) ---
    val live = c.live
    SectionTitle("Traduire le télétexte (ARD, ZDF…)")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Traduction en direct", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = live.enabled, onCheckedChange = { live.changeEnabled(it); c.restart() })
    }
    var keyText by remember { mutableStateOf(live.apiKey) }
    OutlinedTextField(
        keyText, { keyText = it }, singleLine = true, label = { Text("Clé DeepL (se termine par :fx si gratuite)") },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Button({ live.changeKey(keyText); c.restart() }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Enregistrer la clé et relancer") }
    Spacer(Modifier.height(6.dp))
    ChipRow(listOf("AR", "FR", "EN-GB"), live.target, { when (it) { "AR" -> "العربية"; "FR" -> "Français"; else -> "English" } }) { live.changeTarget(it) }
    if (live.status.isNotBlank()) {
        Text(live.status, color = if (live.status.contains("refus") || live.status.contains("Quota") || live.status.contains("inaccess") || live.status.startsWith("Pas de") || live.status.contains("manquante")) MaterialTheme.colorScheme.error else KeyGreen,
            fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Afficher aussi le texte d'origine", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = live.showOriginal, onCheckedChange = { live.changeShowOriginal(it) })
    }
    Hint("Le télétexte est lu dans le flux de la chaîne puis traduit. Ça ne marche que sur les chaînes qui envoient des sous-titres télétexte. Si la traduction est en avance ou en retard, utilise « Retard vidéo » dans le panneau Audio.")

    // --- SRT : lecteur à part, démarré au même état que le film (EPG) ---
    SectionTitle("Sous-titres SRT (lecteur à part)")
    UrlField("URL du fichier .srt (http://…)", "Charger") { subs.loadUrl(it, eventBegin) }
    OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.padding(top = 6.dp)) { Text("Choisir un fichier .srt") }
    if (subs.status.isNotBlank()) {
        Text("${if (subs.fileName.isNotBlank()) subs.fileName + " — " else ""}${subs.status}",
            color = if (subs.status.startsWith("Erreur") || subs.status.startsWith("Aucune")) MaterialTheme.colorScheme.error else KeyGreen,
            fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (subs.loaded) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Afficher les sous-titres", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = subs.visible, onCheckedChange = { subs.visible = it })
        }
        SectionTitle("Synchro")
        val pos by produceState(subs.positionMs()) { while (true) { value = subs.positionMs(); delay(500) } }
        Text("Temps du film : ${fmtHms(pos)}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Décalage : ${"%+.1f".format(subs.offsetMs / 1000f)} s", color = Pal.Muted, fontSize = 13.sp)
        Hint("Le SRT démarre à l'heure de début du programme (EPG). Si le film a commencé plus tard (pubs), corrige ci-dessous.")
        Button({ subs.syncNextCueNow() }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Afficher la prochaine réplique MAINTENANT") }
        Hint("Écoute une phrase du film puis appuie dès qu'elle est dite.")
        OffsetRow(subs, -300_000, -60_000, -10_000)
        OffsetRow(subs, 10_000, 60_000, 300_000)
        OffsetRow(subs, -1000, -500, -100)
        OffsetRow(subs, 100, 500, 1000)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ subs.startFromEpg(eventBegin) }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Début EPG", fontSize = 12.sp) }
            OutlinedButton({ subs.startNow() }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Début = maintenant", fontSize = 12.sp) }
            OutlinedButton({ subs.clear() }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Retirer", fontSize = 12.sp) }
        }
    }

    // --- Apparence ---
    SectionTitle("Apparence (style OSN)")
    ChipRow(SubStyle.values().toList(), subs.style, { it.label }) { subs.style = it; subs.saveStyle() }
    LabeledSlider("Taille", subs.textSize, 18f..56f, onFinished = { subs.saveStyle() }) { subs.textSize = it }
    LabeledSlider("Hauteur", subs.bottomDp, 0f..220f, onFinished = { subs.saveStyle() }) { subs.bottomDp = it }
    ChipRow(listOf(false, true), subs.yellow, { if (it) "Jaune" else "Blanc" }) { subs.yellow = it; subs.saveStyle() }
    SectionTitle("Encodage du fichier")
    ChipRow(Enc.values().toList(), subs.enc, { it.label }) { subs.changeEnc(it) }
    Hint("Pour l'arabe, \"Auto\" détecte UTF-8 ou Windows-1256. Si les lettres sont illisibles, essaie « Arabe 1256 ».")

    // --- Pistes propres à la chaîne (DVB / télétexte) ---
    SectionTitle("Sous-titres de la chaîne")
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Activer automatiquement", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = c.autoSubs, onCheckedChange = { c.changeAutoSubs(it) })
    }
    TrackList(c.subTracks, c.selectedSub, "Aucune piste de sous-titres dans cette chaîne") { c.selectSub(it) }
}

private fun fmtHms(ms: Long): String {
    val neg = ms < 0
    val t = kotlin.math.abs(ms) / 1000
    return (if (neg) "−" else "") + "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
}

@Composable
private fun OffsetRow(subs: SubtitleEngine, a: Long, b: Long, c: Long) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(a, b, c).forEach { d ->
            val abs = kotlin.math.abs(d)
            val label = (if (d > 0) "+" else "−") + when {
                abs >= 60_000 -> "${abs / 60_000} min"
                abs >= 1000 -> "${abs / 1000} s"
                else -> "0,${abs / 100} s"
            }
            FilledTonalButton({ subs.nudge(d) }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 13.sp) }
        }
    }
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

    SectionTitle("Netteté VLC (recommandée)")
    LabeledSlider("Netteté", c.vlcSharpen, 0f..2f, onFinished = { c.changeVlcSharpen(c.vlcSharpen) }) { c.previewVlcSharpen(it) }
    Hint("Mémorisé pour toutes les chaînes. Relâche le curseur pour appliquer (la chaîne se relance).")

    SectionTitle("Netteté GPU (Android 13+)")
    if (c.sharpnessSupported) {
        LabeledSlider("Netteté GPU", c.image.sharpness, 0f..2f) { c.updateImage(c.image.copy(sharpness = it)) }
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
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
    Text(t.uppercase(), color = Pal.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
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
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onFinished: (() -> Unit)? = null, set: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text("$label : ${"%.2f".format(value)}", color = Color.White, fontSize = 14.sp)
        Slider(value, set, valueRange = range, onValueChangeFinished = onFinished)
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

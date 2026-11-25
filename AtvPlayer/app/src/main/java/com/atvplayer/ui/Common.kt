package com.atvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.atvplayer.data.Channel
import com.atvplayer.data.OpenWebIfApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
fun fmtTime(sec: Long): String = hm.format(Date(sec * 1000))

/** Picon avec plusieurs URL de secours ; affiche les initiales si aucune n'existe. */
@Composable
fun Picon(api: OpenWebIfApi, ch: Channel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val urls = remember(ch.ref, api) { api.piconUrls(ch) }
    var idx by remember(ch.ref, api) { mutableIntStateOf(0) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Pal.TileBrush), contentAlignment = Alignment.Center) {
        if (idx < urls.size) {
            val url = urls[idx]
            val req = remember(url, api) {
                ImageRequest.Builder(ctx).data(url)
                    .apply { api.authHeader?.let { addHeader("Authorization", it) } }
                    .crossfade(true).build()
            }
            AsyncImage(
                model = req, contentDescription = ch.name,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit,
                onError = { idx++ },
            )
        } else {
            Text(ch.name.filter { it.isLetterOrDigit() }.take(3).uppercase(), color = Pal.Accent, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

val KeyRed = Color(0xFFEF4444)
val KeyGreen = Color(0xFF22C55E)
val KeyYellow = Color(0xFFFACC15)
val KeyBlue = Color(0xFF3B82F6)

/** Les 4 touches couleur : rouge=Audio, vert=Sous-titres, jaune=Image, bleu=Qualité. */
@Composable
fun ColorKeys(
    onRed: () -> Unit, onGreen: () -> Unit, onYellow: () -> Unit, onBlue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorKey(KeyRed, Color.White, "Audio", Icons.Default.VolumeUp, onRed)
        ColorKey(KeyGreen, Color.White, "Sous-titres", Icons.Default.ClosedCaption, onGreen)
        ColorKey(KeyYellow, Color(0xFF1A1400), "Image", Icons.Default.WbSunny, onYellow)
        ColorKey(KeyBlue, Color.White, "Qualité", Icons.Default.Tune, onBlue)
    }
}

@Composable
private fun ColorKey(color: Color, fg: Color, label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Petit badge (HD, SUB…) */
@Composable
fun Badge(text: String, highlight: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .then(if (highlight) Modifier.background(Pal.Accent) else Modifier.border(1.dp, Color(0x47FFFFFF), RoundedCornerShape(6.dp)))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Text(text, color = if (highlight) Color(0xFF04121A) else Color(0xFFD7DEF0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp) }
}

fun qualityOf(name: String): String? = when {
    Regex("\\b(4k|uhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "4K"
    Regex("\\b(hd|fhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "HD"
    else -> null
}

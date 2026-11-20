package com.atvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Pal.SurfaceHi), contentAlignment = Alignment.Center) {
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
            Text(ch.name.take(3).uppercase(), color = Pal.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

val KeyRed = Color(0xFFE53935)
val KeyGreen = Color(0xFF43A047)
val KeyYellow = Color(0xFFFDD835)
val KeyBlue = Color(0xFF1E88E5)

/** Les 4 touches couleur de télécommande : rouge=Audio, vert=Sous-titres, jaune=Image, bleu=Qualité. */
@Composable
fun ColorKeys(
    onRed: () -> Unit, onGreen: () -> Unit, onYellow: () -> Unit, onBlue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorKey(KeyRed, "Audio", Color.White, onRed)
        ColorKey(KeyGreen, "Sous-titres", Color.White, onGreen)
        ColorKey(KeyYellow, "Image", Color.Black, onYellow)
        ColorKey(KeyBlue, "Qualité", Color.White, onBlue)
    }
}

@Composable
private fun ColorKey(color: Color, label: String, textColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

package com.atvplayer.ui

import androidx.compose.foundation.background
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
    Box(modifier.clip(RectangleShape).background(Pal.SurfaceHi), contentAlignment = Alignment.Center) {
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

val KeyRed = Color(0xFFE5301F)
val KeyGreen = Color(0xFF4CAF2E)
val KeyYellow = Color(0xFFF2C500)
val KeyBlue = Color(0xFF1E88C7)

/** Les 4 touches couleur façon Metrix : carré de couleur + libellé. Rouge=Audio, vert=Sous-titres, jaune=Image, bleu=Qualité. */
@Composable
fun ColorKeys(
    onRed: () -> Unit, onGreen: () -> Unit, onYellow: () -> Unit, onBlue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorKey(KeyRed, "Audio", Icons.Default.VolumeUp, onRed)
        ColorKey(KeyGreen, "Sous-titres", Icons.Default.ClosedCaption, onGreen)
        ColorKey(KeyYellow, "Image", Icons.Default.WbSunny, onYellow)
        ColorKey(KeyBlue, "Qualité", Icons.Default.Tune, onBlue)
    }
}

@Composable
private fun ColorKey(color: Color, label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

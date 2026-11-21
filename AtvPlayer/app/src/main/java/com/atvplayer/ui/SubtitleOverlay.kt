package com.atvplayer.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atvplayer.SubStyle
import com.atvplayer.SubtitleEngine
import com.atvplayer.TransMode
import kotlinx.coroutines.delay

/** Police arabe (Tajawal) téléchargée au build ; repli sur la police du système si absente. */
@Composable
private fun rememberSubFont(): FontFamily {
    val ctx = LocalContext.current
    return remember {
        try { FontFamily(Typeface.createFromAsset(ctx.assets, "fonts/tajawal_bold.ttf")) }
        catch (_: Throwable) { FontFamily.Default }
    }
}

/** Affiche la réplique courante du .srt, style chaînes arabes (blanc, contour noir, gras), avec traduction optionnelle. */
@Composable
fun BoxScope.SubtitleOverlay(e: SubtitleEngine) {
    if (!e.loaded || !e.visible) return
    // (texte original, traduction si prête)
    var shown by remember { mutableStateOf<Pair<String, String?>?>(null) }
    LaunchedEffect(e.cues, e.offsetMs, e.originMs, e.transOn) {
        while (true) {
            val cue = e.cueAt(e.positionMs())
            shown = if (cue == null) null else {
                if (e.transOn) e.prefetch(cue.idx)
                cue.text to e.translated(cue)
            }
            delay(120)
        }
    }
    val (orig, tr) = shown ?: return
    val font = rememberSubFont()
    val fill = if (e.yellow) Color(0xFFFFEB3B) else Color.White

    val main: String
    var second: String? = null
    when {
        !e.transOn || e.transMode == TransMode.Original -> main = orig
        e.transMode == TransMode.Translated -> main = tr ?: orig
        else -> { main = tr ?: orig; if (tr != null) second = orig }
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.82f).padding(bottom = e.bottomDp.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StyledSub(main, e.style, e.textSize, font, fill, 1f)
        second?.let {
            Spacer(Modifier.height(4.dp))
            StyledSub(it, e.style, e.textSize * 0.68f, font, fill, 0.85f)
        }
    }
}

@Composable
private fun StyledSub(t: String, style: SubStyle, size: Float, font: FontFamily, fill: Color, alpha: Float) {
    val base = TextStyle(
        fontFamily = font, fontWeight = FontWeight.Bold, fontSize = size.sp,
        lineHeight = (size * 1.35f).sp, textAlign = TextAlign.Center,
    )
    Box(contentAlignment = Alignment.Center) {
        when (style) {
            SubStyle.Osn -> {
                Text(t, style = base.copy(color = Color.Black.copy(alpha = alpha), drawStyle = Stroke(width = 9f, join = StrokeJoin.Round)))
                Text(t, style = base.copy(color = fill.copy(alpha = alpha), shadow = Shadow(Color(0x99000000), Offset(2f, 3f), 6f)))
            }
            SubStyle.Box -> Text(
                t, style = base.copy(color = fill.copy(alpha = alpha)),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
            SubStyle.Plain -> Text(t, style = base.copy(color = fill.copy(alpha = alpha), shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)))
        }
    }
}

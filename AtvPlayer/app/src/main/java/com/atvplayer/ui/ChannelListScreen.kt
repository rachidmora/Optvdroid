package com.atvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.atvplayer.MainViewModel
import com.atvplayer.data.Channel
import com.atvplayer.data.OpenWebIfApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
fun fmtTime(sec: Long): String = hm.format(Date(sec * 1000))

@Composable
fun Picon(api: OpenWebIfApi, ch: Channel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val req = remember(ch.ref, api) {
        ImageRequest.Builder(ctx).data(api.piconUrl(ch))
            .apply { api.authHeader?.let { addHeader("Authorization", it) } }
            .crossfade(true).build()
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Pal.SurfaceHi), contentAlignment = Alignment.Center) {
        AsyncImage(req, ch.name, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) { kotlinx.coroutines.delay(15_000); value = System.currentTimeMillis() / 1000 }
    }
    val list = remember(vm.channels, query) {
        if (query.isBlank()) vm.channels else vm.channels.filter { it.name.contains(query, true) }
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AtvPlayer", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Pal.Text, modifier = Modifier.weight(1f))
            IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualiser", tint = Pal.Muted) }
            IconButton(onOpenSettings) { Icon(Icons.Default.Settings, "Réglages", tint = Pal.Muted) }
        }

        OutlinedTextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("Rechercher une chaîne") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
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

        vm.error?.let {
            Text("Erreur : $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.ref + it.number }) { ch ->
                ChannelRow(vm, ch, nowSec)
            }
        }
    }
}

@Composable
private fun ChannelRow(vm: MainViewModel, ch: Channel, nowSec: Long) {
    val now = vm.now[ch.key]
    val next = vm.next[ch.key]
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface)
            .clickable { vm.play(ch) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ch.number}", color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.width(30.dp))
        Picon(vm.api, ch, Modifier.size(width = 76.dp, height = 48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = Pal.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Text("${fmtTime(now.begin)}  ${now.title}", color = Pal.Text.copy(alpha = .85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { now.progress(nowSec) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Pal.Accent, trackColor = Pal.SurfaceHi,
                )
                if (next != null) Text("${fmtTime(next.begin)}  ${next.title}", color = Pal.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Pas d'EPG", color = Pal.Muted, fontSize = 13.sp)
            }
        }
    }
}

package com.atvplayer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atvplayer.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    var config by mutableStateOf(prefs.loadConfig()); private set
    var api by mutableStateOf(OpenWebIfApi(config)); private set

    var bouquets by mutableStateOf<List<Bouquet>>(emptyList()); private set
    var selected by mutableStateOf<Bouquet?>(null); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var now by mutableStateOf<Map<String, EpgEvent>>(emptyMap()); private set
    var next by mutableStateOf<Map<String, EpgEvent>>(emptyMap()); private set
    var current by mutableStateOf<Channel?>(null); private set
    var showList by mutableStateOf(true)
    var keyHandler: ((Int) -> Boolean)? = null
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private var epgJob: Job? = null

    init { if (config.host.isNotBlank()) refresh() }

    fun saveConfig(c: BoxConfig) {
        prefs.saveConfig(c)
        config = c
        api = OpenWebIfApi(c)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                bouquets = api.bouquets()
                bouquets.firstOrNull()?.let { selectBouquet(it) }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally { loading = false }
        }
    }

    fun selectBouquet(b: Bouquet) {
        selected = b
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            try {
                channels = api.channels(b.ref)
                error = null
                if (current == null && channels.isNotEmpty()) {
                    current = channels.firstOrNull { it.ref == prefs.lastRef } ?: channels.first()
                }
            } catch (e: Exception) { error = e.message; return@launch }
            while (true) {
                try {
                    now = api.epgNow(b.ref)
                    next = api.epgNext(b.ref)
                } catch (_: Exception) { /* EPG facultatif */ }
                delay(30_000)
            }
        }
    }

    fun play(ch: Channel) { current = ch; prefs.lastRef = ch.ref }

    /** Zapping +1 / -1 dans le bouquet courant. */
    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.ref == current?.ref }.coerceAtLeast(0)
        play(channels[(i + delta + channels.size) % channels.size])
    }
}

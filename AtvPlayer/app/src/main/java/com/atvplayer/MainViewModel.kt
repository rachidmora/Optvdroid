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
    var satellites by mutableStateOf<List<Bouquet>>(emptyList()); private set
    var favorites by mutableStateOf(prefs.loadFavorites()); private set

    /** Favoris, puis bouquets, puis satellites. */
    val categories: List<Bouquet> get() = listOf(Bouquet.Favorites) + bouquets + satellites
    fun isFavorite(ch: Channel) = favorites.any { it.key == ch.key }
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
        posterRequested.clear(); posters = emptyMap()
        api = OpenWebIfApi(c)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                bouquets = api.bouquets()
                satellites = try { api.satellites() } catch (_: Exception) { emptyList() }
                bouquets.firstOrNull()?.let { selectBouquet(it) }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally { loading = false }
        }
    }

    private val requestedEpg = HashSet<String>()

    var posters by mutableStateOf<Map<String, String?>>(emptyMap()); private set
    private val posterRequested = HashSet<String>()

    /** Poster TMDB du programme (si une clé est configurée). */
    fun ensurePoster(title: String) {
        if (config.tmdbKey.isBlank() || !posterRequested.add(title)) return
        viewModelScope.launch {
            val url = try { Tmdb(config.tmdbKey).poster(title) } catch (_: Exception) { null }
            posters = posters + (title to url)
        }
    }

    /** EPG chargé à la demande (une chaîne à la fois) pour les satellites et favoris. */
    fun ensureEpg(ch: Channel) {
        val k = ch.key
        if (selected?.kind == 0 || !requestedEpg.add(k)) return
        viewModelScope.launch {
            val (n, nx) = api.epgOne(ch)
            if (n != null) now = now + (k to n)
            if (nx != null) next = next + (k to nx)
        }
    }

    fun toggleFavorite(ch: Channel) {
        favorites = if (isFavorite(ch)) favorites.filter { it.key != ch.key } else favorites + ch
        prefs.saveFavorites(favorites)
        if (selected?.kind == 2) channels = favorites.mapIndexed { i, c -> c.copy(number = i + 1) }
    }

    fun selectBouquet(b: Bouquet) {
        selected = b
        epgJob?.cancel()
        requestedEpg.clear()
        if (b.kind == 2) {
            channels = favorites.mapIndexed { i, c -> c.copy(number = i + 1) }
            return
        }
        epgJob = viewModelScope.launch {
            try {
                channels = api.channels(b)
                error = null
                if (current == null && channels.isNotEmpty()) {
                    current = channels.firstOrNull { it.ref == prefs.lastRef } ?: channels.first()
                }
            } catch (e: Exception) { error = e.message; return@launch }
            if (b.kind != 0) return@launch
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

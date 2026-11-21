package com.atvplayer.data

data class BoxConfig(
    val host: String = "",
    val httpPort: Int = 80,
    val streamPort: Int = 8001,
    val user: String = "",
    val pass: String = "",
)

/** kind : 0 = bouquet, 1 = satellite, 2 = favoris (locaux). */
data class Bouquet(val ref: String, val name: String, val kind: Int = 0) {
    companion object {
        const val FAV_REF = "__favorites__"
        val Favorites = Bouquet(FAV_REF, "★ Favoris", 2)
    }
}

data class Channel(val ref: String, val name: String, val number: Int) {
    /** Clé normalisée pour faire correspondre les refs (avec/sans ':' final). */
    val key: String get() = ref.trimEnd(':')
}

data class EpgEvent(
    val title: String,
    val shortDesc: String,
    val longDesc: String,
    val begin: Long,      // secondes epoch
    val duration: Long,   // secondes
) {
    val end: Long get() = begin + duration
    fun progress(nowSec: Long): Float =
        if (duration <= 0) 0f else ((nowSec - begin).toFloat() / duration).coerceIn(0f, 1f)

    fun remainingMin(nowSec: Long): Long = ((end - nowSec) / 60).coerceAtLeast(0)
    val description: String get() = longDesc.ifBlank { shortDesc }
}

data class ImageSettings(
    val saturation: Float = 1f,   // 0..2
    val contrast: Float = 1f,     // 0.5..1.5
    val brightness: Float = 0f,   // -0.5..0.5
    val sharpness: Float = 0f,    // 0..2 (Android 13+)
) {
    val isDefault get() = this == ImageSettings()
}

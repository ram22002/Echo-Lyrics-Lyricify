package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lyricify. The title/artist pair is resolved to an ISRC through credits.fm, then the lyric
 * document is fetched from api.lyricify.app. credits.fm expects the title under `name` (not
 * `title`); with the wrong key it answers 400 and no ISRC is ever found.
 */
internal class LyricifySource : LyricsSource {

    private val throttle = Throttle(800)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        val isrc = resolveIsrc(track, settings) ?: return emptyList()
        return listOf(
            Lyrics(
                id = "lyricify:$isrc",
                title = track.title,
                subtitle = query.artist + " • Lyricify",
                lyrics = null,
                extras = mapOf("isrc" to isrc),
            ),
        )
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val isrc = lyrics.extras["isrc"] ?: throw LyricsException("Lyricify candidate is missing its ISRC")
        val encoded = Http.encode(Codec.encode(isrc.toByteArray(Charsets.UTF_8)))
        val username = "echo" + (100000..999999).random()
        throttle.await()
        val body = Http.get(
            "$API?username=$username&isrc=$encoded",
            mapOf("Accept-Encoding" to "gzip", "Accept" to "application/json"),
        )
        val root = Http.json(body).obj() ?: throw LyricsException("Empty Lyricify response")
        if (root.bool("isInstrumental") == true) throw LyricsException("Lyricify marks this track as instrumental")
        val text = root.str("text") ?: throw LyricsException("Lyricify returned no lyrics text")
        val lines = Lrc.parse(text)
        if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, text.contains("[from:AppleSyllable]") && lines.any { it.words.size > 1 }))
        val plainLines = Lrc.parsePlain(text)
        if (plainLines.isNotEmpty()) return lyrics.copy(lyrics = plain(plainLines.joinToString("\n")))
        throw LyricsException("Lyricify payload could not be parsed")
    }

    private suspend fun resolveIsrc(track: Track, settings: Settings?): String? {
        val base = settings?.getString(KEY_CREDITS)?.trim().orEmpty().ifEmpty { CREDITS }
        throttle.await()
        val payload = buildJsonObject {
            put("name", track.title)
            put("artist", track.query().artist)
        }
        val body = runCatching {
            Http.postJson(
                base,
                payload.toString(),
                mapOf(
                    "Accept" to "application/json",
                    "Accept-Encoding" to "identity",
                    "User-Agent" to CHROME_UA,
                ),
            )
        }.getOrNull() ?: return null
        return runCatching { Http.json(body).obj()?.str("isrc") }.getOrNull()
    }

    companion object {
        const val KEY_CREDITS = "credits_endpoint"
        private const val API = "https://api.lyricify.app/lyrics/get/mobile/new"
        private const val CREDITS = "https://api.credits.fm/v1/resolve/track"
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

        fun settings(): List<Setting> = listOf(
            dev.brahmkshatriya.echo.common.settings.SettingTextInput(
                title = "Credits resolver",
                key = KEY_CREDITS,
                summary = "Leave blank for https://api.credits.fm/v1/resolve/track",
                defaultValue = "",
            ),
        )
    }
}

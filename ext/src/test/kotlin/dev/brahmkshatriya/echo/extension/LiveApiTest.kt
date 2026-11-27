package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveApiTest {

    @Test
    fun resolvesAnIsrcForAKnownTrack(): Unit = runBlocking {
        assumeTrue(System.getProperty("echo.live") == "1")
        val track = Track(
            id = "live-probe",
            title = "Never Gonna Give You Up",
            artists = listOf(Artist("1", "Rick Astley")),
            duration = 213000L,
        )
        val source = LyricifySource()
        val candidates = source.search(track, null)
        assertTrue("credits.fm returned no ISRC", candidates.isNotEmpty())
        println("live: lyricify isrc=${candidates.first().extras["isrc"]}")
        val loaded = runCatching { source.load(candidates.first(), null) }
        loaded.onSuccess { println("live: lyricify lyrics=${it.lyrics?.javaClass?.simpleName}") }
        loaded.onFailure { println("live: lyricify load failed -> ${it.message}") }
    }
}

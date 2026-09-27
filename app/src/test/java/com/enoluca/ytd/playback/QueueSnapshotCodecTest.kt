package com.enoluca.ytd.playback

import com.enoluca.ytd.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueueSnapshotCodecTest {

    @Test
    fun `queue ids survive a round trip, radio included`() {
        val ids = listOf("12", "7", "radio:654c01c2-d1a3-45dd-903e-b011e1e077b6", "99")
        assertEquals(ids, QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(ids)))
        assertEquals(emptyList<String>(), QueueSnapshotCodec.decode(""))
    }

    @Test
    fun `restore starts at the same item, or the next one that still exists`() {
        // Saved at index 2; items 0, 2, 3 survived → index 1 in the restored queue.
        assertEquals(1, QueueSnapshotCodec.restoredIndex(2, listOf(0, 2, 3)))
        // Item 2 was deleted → continue with the next surviving one (3).
        assertEquals(1, QueueSnapshotCodec.restoredIndex(2, listOf(0, 3)))
        // Nothing after it survived → start from the top.
        assertEquals(0, QueueSnapshotCodec.restoredIndex(5, listOf(0, 1)))
        assertEquals(0, QueueSnapshotCodec.restoredIndex(0, emptyList()))
    }

    @Test
    fun `radio and library media ids are told apart`() {
        assertEquals("abc", PlaybackItems.radioIdOf("radio:abc"))
        assertEquals("radio:abc", PlaybackItems.radioMediaId("abc"))
        assertNull(PlaybackItems.radioIdOf("42"))
        assertNull(PlaybackItems.radioIdOf("radio:"))
        assertNull(PlaybackItems.radioIdOf(null))
    }

    /** The release build: 0.3.0, and a versionCode above every published release (0.2.0 = 2000). */
    @Test
    fun `version is 0_3_0 and its versionCode is higher than 0_2_0`() {
        val gradle = File("build.gradle.kts").readText()
        val name = Regex("""versionName = "([^"]+)"""").find(gradle)!!.groupValues[1]
        assertEquals("0.3.0", name)
        assertEquals(3000, AppVersion.versionCodeFor(name))
        assertTrue(AppVersion.versionCodeFor(name) > AppVersion.versionCodeFor("0.2.0"))
        assertTrue(AppVersion.parse(name)!! > AppVersion.parse("v0.2.0")!!)
    }
}

class RadioMetadataTextTest {
    @Test
    fun `machine data in stream metadata is replaced by the station's details`() {
        val json = """{"songInfo":{"show":{"prg_title":"Suite 102.5"}}}"""
        org.junit.Assert.assertTrue(RadioMetadataText.isMachineData(json))
        org.junit.Assert.assertTrue(RadioMetadataText.isMachineData("<xml/>"))
        org.junit.Assert.assertFalse(RadioMetadataText.isMachineData("Dua Lipa - Houdini"))
        org.junit.Assert.assertFalse(RadioMetadataText.isMachineData(null))

        val station = androidx.media3.common.MediaMetadata.Builder().setTitle("RTL 102.5").setArtist("Bologna · Hits").build()
        val live = station.buildUpon().setDescription(json).build()
        val cleaned = RadioMetadataText.clean(live, station)
        assertNull(cleaned.description)
        assertEquals("RTL 102.5", cleaned.title)
        assertEquals("Bologna · Hits", cleaned.artist)

        val song = station.buildUpon().setTitle("Dua Lipa - Houdini").build()
        org.junit.Assert.assertSame("real song info is kept as is", song, RadioMetadataText.clean(song, station))
    }
}

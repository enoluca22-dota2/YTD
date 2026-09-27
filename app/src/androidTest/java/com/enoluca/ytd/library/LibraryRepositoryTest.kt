package com.enoluca.ytd.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enoluca.ytd.data.local.datastore.SettingsDataStore
import com.enoluca.ytd.data.local.db.AppDatabase
import com.enoluca.ytd.data.local.db.DownloadEntity
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.data.local.db.MediaOrigin
import com.enoluca.ytd.data.local.db.MediaType
import com.enoluca.ytd.data.model.DownloadCategory
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.data.model.FormatKind
import com.enoluca.ytd.download.CompletedDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Library on a device: Room (in memory) + real files. Downloads are fed in exactly as the
 * download engine reports them (CompletedDownload).
 */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: LibraryRepository
    private lateinit var indexer: LibraryIndexer
    private var nextDownloadId = 1L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artwork = ArtworkCache(context)
        repository = LibraryRepository(context, db, artwork)
        indexer = LibraryIndexer(context, db, repository, artwork, SettingsDataStore(context), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        TestMedia.cleanUp(context)
    }

    /** Simulates the engine publishing a download: a real WAV file + the job's metadata. */
    private suspend fun download(
        title: String,
        seconds: Int = 2,
        category: DownloadCategory = DownloadCategory.MUSIC,
        batchId: String? = null,
        sourceIndex: Int? = null,
        uploader: String? = "Some Artist",
    ): LibraryMediaEntity {
        val file = TestMedia.wav(context, title.replace(' ', '_'), seconds)
        val now = System.currentTimeMillis()
        val entity = DownloadEntity(
            id = nextDownloadId++,
            sourceUrl = "https://www.youtube.com/watch?v=${title.hashCode()}",
            webpageUrl = "https://www.youtube.com/watch?v=${title.hashCode()}",
            title = title,
            thumbnailUrl = null,
            extractorKey = "Youtube",
            formatId = "bestaudio",
            formatKind = FormatKind.AUDIO,
            resolutionLabel = null,
            container = "wav",
            videoCodec = null,
            audioCodec = null,
            requiresAudioMerge = false,
            requiresAudioExtraction = false,
            category = category,
            fileBaseName = title,
            status = DownloadStatus.COMPLETED,
            createdAt = now,
            updatedAt = now,
            batchId = batchId,
            batchIndex = sourceIndex,
            uploader = uploader,
            playlistSourceIndex = sourceIndex,
        )
        indexer.onDownloadCompleted(
            CompletedDownload(entity, TestMedia.uriOf(file), file.length(), "wav", "audio/x-wav", "Music/ENAGELYUCA"),
        )
        return repository.findByUri(TestMedia.uriOf(file))!!
    }

    private suspend fun titles(playlistId: Long) = repository.getPlaylistTracks(playlistId).map { it.media.title }

    @Test
    fun aCompletedDownloadBecomesAPlayableLibraryItemWithItsMetadata() = runBlocking<Unit> {
        val item = download("Song A", seconds = 3)
        assertEquals("Song A", item.title)
        assertEquals("Some Artist", item.artist)
        assertEquals(MediaType.AUDIO, item.mediaType)
        assertEquals(MediaOrigin.DOWNLOAD, item.origin)
        assertEquals("YouTube", item.sourcePlatform)
        assertEquals("Music/ENAGELYUCA", item.location)
        assertTrue(item.isAvailable)
        assertEquals(3_000.0, (item.durationMs ?: 0).toDouble(), 150.0) // read from the file itself
        assertEquals(1, repository.observeStats().first().count)
        // Indexing the same file again (e.g. a scan) doesn't duplicate it.
        indexer.rescan()
        assertEquals(1, repository.observeAllMedia().first().count { it.uri == item.uri })
    }

    @Test
    fun aDownloadedPlaylistBecomesALibraryPlaylistInItsOriginalOrder() = runBlocking<Unit> {
        val playlistId = repository.linkBatchToSourcePlaylist(
            batchId = "batch-1",
            sourceKey = SourcePlaylistKey.of("youtube", "PLalb", "https://www.youtube.com/playlist?list=PLalb"),
            title = "Best Albanian Music",
            thumbnailUrl = "https://i.ytimg.com/vi/x/hqdefault.jpg",
            sourceUrl = "https://www.youtube.com/playlist?list=PLalb",
            sourcePlatform = "YouTube",
        )
        // Parallel downloads finish out of order; #2 failed and is retried at the very end.
        download("Song C", batchId = "batch-1", sourceIndex = 3)
        download("Song A", batchId = "batch-1", sourceIndex = 1)
        download("Song D", batchId = "batch-1", sourceIndex = 4)
        assertEquals(listOf("Song A", "Song C", "Song D"), titles(playlistId))
        download("Song B", batchId = "batch-1", sourceIndex = 2) // the retry succeeded
        assertEquals(listOf("Song A", "Song B", "Song C", "Song D"), titles(playlistId))

        val summary = repository.observePlaylists().first().single()
        assertEquals("Best Albanian Music", summary.playlist.title)
        assertEquals("YouTube", summary.playlist.sourcePlatform)
        assertEquals(4, summary.itemCount)
        assertTrue(summary.totalDurationMs > 0)

        // Downloading the same playlist again later fills the same Library playlist.
        val again = repository.linkBatchToSourcePlaylist("batch-2", SourcePlaylistKey.of("youtube", "PLalb", "x"), "Best Albanian Music", null, "x", "YouTube")
        assertEquals(playlistId, again)
        download("Song E", batchId = "batch-2", sourceIndex = 5)
        assertEquals(listOf("Song A", "Song B", "Song C", "Song D", "Song E"), titles(playlistId))

        // A single download never creates a playlist.
        download("Single")
        assertEquals(1, repository.observePlaylists().first().size)
    }

    @Test
    fun aNewDownloadCanBeAddedToAnExistingPlaylistReorderedAndRemovedWithoutLosingTheFile() = runBlocking<Unit> {
        val a = download("Song A")
        val b = download("Song B")
        val c = download("Song C")
        val workout = repository.createPlaylist("Workout")
        assertEquals(3, repository.addToPlaylist(workout, listOf(a.id, b.id, c.id)))

        val d = download("Song D")
        assertEquals(1, repository.addToPlaylist(workout, listOf(d.id)))
        assertEquals(listOf("Song A", "Song B", "Song C", "Song D"), titles(workout))
        assertEquals(0, repository.addToPlaylist(workout, listOf(d.id))) // no duplicates

        repository.movePlaylistItem(workout, from = 3, to = 0)
        assertEquals(listOf("Song D", "Song A", "Song B", "Song C"), titles(workout))
        assertEquals(listOf(0, 1, 2, 3), repository.getPlaylistTracks(workout).map { it.position })

        repository.removeFromPlaylist(workout, d.id)
        assertEquals(listOf("Song A", "Song B", "Song C"), titles(workout))
        assertEquals(listOf(0, 1, 2), repository.getPlaylistTracks(workout).map { it.position })
        // The media itself is untouched.
        assertNotNull(repository.getMedia(d.id))
        assertTrue(File(android.net.Uri.parse(d.uri).path!!).exists())
    }

    @Test
    fun deletingAPlaylistKeepsItsMediaUnlessAskedToDeleteTheMediaToo() = runBlocking<Unit> {
        val a = download("Keep A")
        val b = download("Keep B")
        val keep = repository.createPlaylist("Keep")
        repository.addToPlaylist(keep, listOf(a.id, b.id))
        assertNull(repository.deletePlaylist(keep, deleteMedia = false))
        assertNull(repository.getPlaylist(keep))
        assertNotNull(repository.getMedia(a.id))
        assertNotNull(repository.getMedia(b.id))
        assertTrue(MediaFiles.exists(context, a.uri))

        val gone = repository.createPlaylist("Gone")
        repository.addToPlaylist(gone, listOf(a.id))
        val result = repository.deletePlaylist(gone, deleteMedia = true)
        assertEquals(LibraryRepository.DeleteResult.Done(deleted = 1, failed = 0), result)
        assertNull(repository.getMedia(a.id))
        assertFalse(MediaFiles.exists(context, a.uri))
        assertNotNull(repository.getMedia(b.id)) // not in that playlist
    }

    @Test
    fun favoritesOnlyChangeMetadata() = runBlocking<Unit> {
        val a = download("Fav A")
        val b = download("Fav B")
        repository.setFavorite(listOf(a.id), true)
        val all = repository.observeAllMedia().first()
        assertEquals(listOf("Fav A"), LibraryFilter.FAVORITES.apply(all).map { it.title })
        assertEquals(2, all.size) // nothing duplicated
        repository.setFavorite(listOf(a.id), false)
        assertFalse(repository.getMedia(a.id)!!.favorite)
        assertFalse(repository.getMedia(b.id)!!.favorite)
    }

    @Test
    fun aFileDeletedOutsideTheAppIsDetectedAndCanBeRemoved() = runBlocking<Unit> {
        val item = download("Vanishing", seconds = 2)
        val other = download("Staying")
        val file = File(android.net.Uri.parse(item.uri).path!!)
        indexer.rescan() // (the device may already hold real ENAGELYUCA files; count relative to that)
        val countBefore = repository.observeStats().first().count
        assertTrue(file.delete())

        val result = indexer.rescan()
        assertTrue(result.missing >= 1)
        val missing = repository.getMedia(item.id)!!
        assertFalse(missing.isAvailable)
        assertTrue(repository.getMedia(other.id)!!.isAvailable)
        // Missing files don't count as Library content.
        assertEquals(countBefore - 1, repository.observeStats().first().count)

        // The file comes back (e.g. SD card re-inserted): available again.
        TestMedia.wav(context, "Vanishing", 2)
        assertEquals(1, indexer.rescan().restored)
        assertTrue(repository.getMedia(item.id)!!.isAvailable)

        // Gone for good: "Remove missing items" forgets it (and only it).
        file.delete()
        indexer.rescan()
        assertEquals(1, repository.forgetMissing())
        assertNull(repository.getMedia(item.id))
        assertNotNull(repository.getMedia(other.id))
    }

    @Test
    fun searchFindsTitlesArtistsFilesAndPlaylistNames() = runBlocking<Unit> {
        download("Morning Run", uploader = "DJ Sunrise")
        download("Evening Chill", uploader = "Lo-Fi Crew")
        download("100% Hits", uploader = "Various")
        val list = repository.createPlaylist("Road Trip")
        repository.addToPlaylist(list, listOf(repository.search("Evening").first().single().id))

        suspend fun search(q: String) = repository.search(q).first().map { it.title }.sorted()
        assertEquals(listOf("Morning Run"), search("morning"))
        assertEquals(listOf("Evening Chill"), search("lo-fi"))
        assertEquals(listOf("Evening Chill"), search("road trip"))
        assertEquals(listOf("Morning Run"), search("Morning_Run.wav")) // file name
        assertEquals(listOf("100% Hits"), search("100%"))
        assertEquals(emptyList<String>(), search("nothing like this"))
    }

    @Test
    fun resumePositionsAndPlayCountsAreRemembered() = runBlocking<Unit> {
        val video = download("Long Video", seconds = 1, category = DownloadCategory.VIDEO)
        // Left at 00:47:12 of a 01:42:35 video.
        val duration = (60 + 42) * 60_000L + 35_000
        repository.saveResumePosition(video.id, 47 * 60_000L + 12_000, duration)
        assertEquals(47 * 60_000L + 12_000, repository.getMedia(video.id)!!.resumePositionMs)
        // Watched to the end: starts over next time.
        repository.saveResumePosition(video.id, duration - 3_000, duration)
        assertEquals(0L, repository.getMedia(video.id)!!.resumePositionMs)

        repository.recordPlay(video.id)
        repository.recordPlay(video.id)
        val played = repository.getMedia(video.id)!!
        assertEquals(2, played.playCount)
        assertNotNull(played.lastPlayedAt)
        assertEquals(video.id, repository.lastPlayed()!!.id)
    }
}

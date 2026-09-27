package com.enoluca.ytd.radio

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enoluca.ytd.YtdApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release audit: EVERY station in the bundled catalog is played through the real PlaybackService
 * (Radio Browser lookup → ExoPlayer → audio actually playing). Depends on 49 outside servers, so
 * it only runs when asked:
 *
 *   gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.radioCatalog=true
 */
@RunWith(AndroidJUnit4::class)
class RadioCatalogPlaybackTest {

    private val app: YtdApplication = ApplicationProvider.getApplicationContext()
    private val connection = app.container.playerConnection

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.stop()
            connection.disconnect()
        }
    }

    @Test
    fun everyCatalogStationPlays() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("radioCatalog") == "true")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.connect() }
        waitUntil(10_000) { connection.state.value.connected }

        val stations = RadioCountries.all.flatMap { app.container.radioRepository.featured(it.code) }
        assertTrue(stations.size >= 45)
        val failures = mutableListOf<String>()
        for (station in stations) {
            runBlocking { app.container.radioRepository.remember(station) }
            connection.playRadio(station)
            val ok = waitUntil(25_000) {
                val s = connection.state.value
                s.current?.radioStationId == station.id && (s.isPlaying || s.error != null)
            } && connection.state.value.isPlaying
            Log.i(TAG, "${if (ok) "PLAYS" else "FAILS"}  ${station.countryCode}  ${station.name}  ${connection.state.value.error ?: ""}")
            if (!ok) failures += "${station.countryCode} ${station.name}: ${connection.state.value.error ?: "timeout"}"
        }
        assertTrue("Stations that did not play:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private companion object {
        const val TAG = "RadioCatalogAudit"
    }
}

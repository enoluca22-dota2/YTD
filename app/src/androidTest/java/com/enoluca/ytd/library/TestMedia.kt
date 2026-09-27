package com.enoluca.ytd.library

import android.content.Context
import android.net.Uri
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Real, playable media files for tests: silent 16-bit mono PCM WAV of a given length. */
object TestMedia {

    fun wav(context: Context, name: String, seconds: Int): File {
        val dir = File(context.filesDir, "test-media").apply { mkdirs() }
        val file = File(dir, "$name.wav")
        val sampleRate = 8_000
        val dataBytes = sampleRate * seconds * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun intLe(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun shortLe(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); intLe(36 + dataBytes); out.writeBytes("WAVE")
            out.writeBytes("fmt "); intLe(16); shortLe(1); shortLe(1); intLe(sampleRate); intLe(sampleRate * 2); shortLe(2); shortLe(16)
            out.writeBytes("data"); intLe(dataBytes)
            out.write(ByteArray(dataBytes))
        }
        return file
    }

    fun uriOf(file: File): String = Uri.fromFile(file).toString()

    fun cleanUp(context: Context) {
        File(context.filesDir, "test-media").deleteRecursively()
    }
}

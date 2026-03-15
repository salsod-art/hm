package com.rootboard

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SoundManager {

    private const val TAG = "RootBoard"
    private const val SOUNDS_DIR = "/data/media/0/RootBoard/sounds"
    private const val CONFIG_FILE = "/data/media/0/RootBoard/active_sound"
    private const val PLAYING_FLAG = "/data/media/0/RootBoard/tmp/playing"

    // Target sample rate and channels to match what apps expect
    private const val TARGET_SAMPLE_RATE = 48000
    private const val TARGET_CHANNELS = 1  // Most mic inputs expect mono

    // Current audio state
    private var audioBuffer: ByteArray? = null
    private var bufferPosition: Int = 0
    private var isPlaying: Boolean = false
    private var currentFile: String = ""
    private var loop: Boolean = false

    /**
     * Called periodically to check if the KSU WebUI has requested a new sound.
     * The WebUI writes the filename to /data/media/0/RootBoard/active_sound
     * and a flag to /data/media/0/RootBoard/tmp/playing
     */
    fun checkForUpdates() {
        try {
            val playingFile = File(PLAYING_FLAG)
            val configFile = File(CONFIG_FILE)

            if (!playingFile.exists()) {
                // WebUI stopped playback
                if (isPlaying) {
                    isPlaying = false
                    audioBuffer = null
                    bufferPosition = 0
                    Log.d(TAG, "Playback stopped by WebUI")
                }
                return
            }

            val requestedFile = configFile.readText().trim()
            if (requestedFile != currentFile || !isPlaying) {
                loadSound(requestedFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkForUpdates error: ${e.message}")
        }
    }

    /**
     * Load a sound file from /RootBoard/sounds/ into memory as raw PCM bytes.
     * Handles WAV directly. For MP3/OGG we read raw and hope ffmpeg pre-converted.
     */
    private fun loadSound(filename: String) {
        try {
            val file = File("$SOUNDS_DIR/$filename")
            if (!file.exists()) {
                Log.e(TAG, "Sound file not found: $filename")
                return
            }

            Log.d(TAG, "Loading sound: $filename (${file.length()} bytes)")

            val rawBytes = file.readBytes()

            // If it's a WAV file, strip the 44-byte header to get raw PCM
            audioBuffer = if (filename.endsWith(".wav", ignoreCase = true)) {
                extractPcmFromWav(rawBytes)
            } else {
                // For MP3/OGG — requires the WebUI to pre-convert via ffmpeg
                // The service.sh can pre-convert to wav before setting active_sound
                rawBytes
            }

            bufferPosition = 0
            isPlaying = true
            currentFile = filename
            loop = File("/data/media/0/RootBoard/tmp/loop").exists()

            Log.d(TAG, "Sound loaded: $filename, PCM bytes: ${audioBuffer?.size}, loop: $loop")

        } catch (e: Exception) {
            Log.e(TAG, "loadSound error: ${e.message}")
            isPlaying = false
        }
    }

    /**
     * Strip WAV header (44 bytes standard) and return raw PCM data
     */
    private fun extractPcmFromWav(wavBytes: ByteArray): ByteArray {
        // Validate RIFF header
        if (wavBytes.size < 44) return wavBytes
        val riff = String(wavBytes.sliceArray(0..3))
        val wave = String(wavBytes.sliceArray(8..11))
        return if (riff == "RIFF" && wave == "WAVE") {
            // Find data chunk — it's usually at offset 44 but can vary
            var dataOffset = 44
            for (i in 12 until minOf(100, wavBytes.size - 4)) {
                if (wavBytes[i] == 'd'.code.toByte() &&
                    wavBytes[i+1] == 'a'.code.toByte() &&
                    wavBytes[i+2] == 't'.code.toByte() &&
                    wavBytes[i+3] == 'a'.code.toByte()) {
                    dataOffset = i + 8  // skip "data" + 4-byte size
                    break
                }
            }
            Log.d(TAG, "WAV data starts at offset $dataOffset")
            wavBytes.sliceArray(dataOffset until wavBytes.size)
        } else {
            wavBytes  // not a real WAV, use as-is
        }
    }

    /**
     * Main method called by AudioHook when an app calls AudioRecord.read()
     * Fills the app's buffer with our audio data instead of real mic data.
     * Returns the number of bytes written, or 0 if nothing to play.
     */
    fun fillAudioBuffer(outputArray: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        checkForUpdates()

        val buf = audioBuffer ?: return 0
        if (!isPlaying || buf.isEmpty()) return 0

        var written = 0
        var outPos = offsetInBytes

        while (written < sizeInBytes) {
            if (bufferPosition >= buf.size) {
                if (loop) {
                    bufferPosition = 0
                } else {
                    // Sound finished — clear playing flag
                    isPlaying = false
                    audioBuffer = null
                    currentFile = ""
                    try {
                        File(PLAYING_FLAG).delete()
                        File(CONFIG_FILE).delete()
                    } catch (_: Exception) {}
                    break
                }
            }

            val remaining = sizeInBytes - written
            val available = buf.size - bufferPosition
            val toCopy = minOf(remaining, available)

            System.arraycopy(buf, bufferPosition, outputArray, outPos, toCopy)
            bufferPosition += toCopy
            outPos += toCopy
            written += toCopy
        }

        // Fill remainder with silence if we ran out
        if (written < sizeInBytes) {
            outputArray.fill(0, offsetInBytes + written, offsetInBytes + sizeInBytes)
            return sizeInBytes
        }

        return written
    }

    /**
     * ByteBuffer variant — for apps that use AudioRecord.read(ByteBuffer, int)
     */
    fun fillAudioBuffer(outputBuffer: ByteBuffer, sizeInBytes: Int): Int {
        val tempArray = ByteArray(sizeInBytes)
        val result = fillAudioBuffer(tempArray, 0, sizeInBytes)
        if (result > 0) {
            outputBuffer.put(tempArray, 0, result)
        }
        return result
    }

    /**
     * Short array variant — for apps that use AudioRecord.read(short[], int, int)
     */
    fun fillAudioBuffer(outputArray: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        val byteArray = ByteArray(sizeInShorts * 2)
        val byteResult = fillAudioBuffer(byteArray, 0, byteArray.size)
        if (byteResult > 0) {
            val bb = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
            for (i in offsetInShorts until offsetInShorts + sizeInShorts) {
                if (bb.remaining() >= 2) outputArray[i] = bb.short
            }
        }
        return sizeInShorts
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying
    fun getCurrentFile(): String = currentFile
}

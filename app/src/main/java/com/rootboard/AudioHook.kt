package com.rootboard

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.nio.ByteBuffer

object AudioHook {

    private const val TAG = "RootBoard"

    fun install(classLoader: ClassLoader) {
        try {
            val audioRecordClass = XposedHelpers.findClass(
                "android.media.AudioRecord",
                classLoader
            )

            hookReadByteArray(audioRecordClass)
            hookReadByteBuffer(audioRecordClass)
            hookReadShortArray(audioRecordClass)

            Log.i(TAG, "AudioRecord hooks installed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install AudioRecord hooks: ${e.message}")
        }
    }

    /**
     * Hook 1: AudioRecord.read(byte[], int, int)
     * Most common — used by Discord, WhatsApp, Telegram, phone calls
     */
    private fun hookReadByteArray(audioRecordClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                audioRecordClass,
                "read",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SoundManager.isCurrentlyPlaying()) return

                        val audioData = param.args[0] as ByteArray
                        val offsetInBytes = param.args[1] as Int
                        val sizeInBytes = param.args[2] as Int

                        val written = SoundManager.fillAudioBuffer(
                            audioData, offsetInBytes, sizeInBytes
                        )
                        param.result = written
                    }
                }
            )
            Log.d(TAG, "Hooked: read(byte[], int, int)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not hook read(byte[], int, int): ${e.message}")
        }
    }

    /**
     * Hook 2: AudioRecord.read(ByteBuffer, int)
     * Used by some media frameworks and newer apps
     */
    private fun hookReadByteBuffer(audioRecordClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                audioRecordClass,
                "read",
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SoundManager.isCurrentlyPlaying()) return

                        val buffer = param.args[0] as ByteBuffer
                        val sizeInBytes = param.args[1] as Int

                        val written = SoundManager.fillAudioBuffer(buffer, sizeInBytes)
                        param.result = written
                    }
                }
            )
            Log.d(TAG, "Hooked: read(ByteBuffer, int)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not hook read(ByteBuffer, int): ${e.message}")
        }
    }

    /**
     * Hook 3: AudioRecord.read(short[], int, int)
     * Used by some audio processing apps
     */
    private fun hookReadShortArray(audioRecordClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                audioRecordClass,
                "read",
                ShortArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SoundManager.isCurrentlyPlaying()) return

                        val audioData = param.args[0] as ShortArray
                        val offsetInShorts = param.args[1] as Int
                        val sizeInShorts = param.args[2] as Int

                        val written = SoundManager.fillAudioBuffer(
                            audioData, offsetInShorts, sizeInShorts
                        )
                        param.result = written
                    }
                }
            )
            Log.d(TAG, "Hooked: read(short[], int, int)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not hook read(short[], int, int): ${e.message}")
        }
    }
}

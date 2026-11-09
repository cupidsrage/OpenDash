package com.opendash.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** What a spoken phrase asks for. */
sealed class VoiceCommand {
    data class OpenApp(val app: AppEntry) : VoiceCommand()
    data class Navigate(val destination: String) : VoiceCommand()
    data class Play(val query: String) : VoiceCommand()
    object Home : VoiceCommand()
    object Apps : VoiceCommand()
    object Back : VoiceCommand()
    data class Unknown(val heard: String) : VoiceCommand()
}

/**
 * Listens through Android's speech recognizer and turns phrases like
 * "open Spotify", "navigate to Walmart" or "play Zach Bryan" into commands.
 */
class VoiceController(
    private val ctx: Context,
    private val apps: AppRepository,
    private val onStatus: (String?) -> Unit,
    private val onCommand: (VoiceCommand) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    fun listen() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Allow the microphone in OpenDash on your phone")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onStatus("Speech recognition isn't available on this phone")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        r.cancel()
        onStatus("Listening…")
        r.startListening(intent)
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    fun parse(text: String): VoiceCommand {
        val t = text.trim()
        val lower = t.lowercase()

        fun after(regex: Regex): String? =
            regex.find(lower)?.let { m -> t.substring(m.range.last + 1).trim().ifEmpty { null } }

        after(Regex("^(navigate to|directions to|take me to|drive to|go to|get me to)\\b"))
            ?.let { return VoiceCommand.Navigate(it) }

        after(Regex("^(open|launch|start|show)\\b"))?.let { name ->
            apps.findByName(name)?.let { return VoiceCommand.OpenApp(it) }
            return VoiceCommand.Unknown(t)
        }

        after(Regex("^(play|listen to)\\b"))?.let { return VoiceCommand.Play(it) }

        return when (lower) {
            "home", "go home", "home screen" -> VoiceCommand.Home
            "apps", "all apps", "show apps" -> VoiceCommand.Apps
            "back", "go back" -> VoiceCommand.Back
            else -> apps.findByName(lower)?.let { VoiceCommand.OpenApp(it) } ?: VoiceCommand.Unknown(t)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStatus("Working on it…") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onStatus("“$text”")
        }

        override fun onResults(results: Bundle?) {
            val options = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (options.isEmpty()) {
                onStatus("Didn't catch that")
                return
            }
            // Prefer the first alternative that maps to a real command.
            val cmd = options.map { parse(it) }.firstOrNull { it !is VoiceCommand.Unknown }
                ?: VoiceCommand.Unknown(options.first())
            onCommand(cmd)
        }

        override fun onError(error: Int) {
            onStatus(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for voice"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone in OpenDash on your phone"
                    else -> "Voice isn't working right now"
                }
            )
        }
    }
}

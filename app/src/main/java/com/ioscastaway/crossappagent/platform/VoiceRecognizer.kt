package com.ioscastaway.crossappagent.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * [SpeechRecognizer] as a Flow.
 *
 * Coming from iOS this is the closest thing to `SFSpeechRecognizer`, with one structural difference:
 * the recognizer itself is a replaceable system component. Whichever app holds the
 * `RecognitionService` role does the work, so the same code runs against Google's recognizer, a
 * Samsung one, or your own.
 *
 * Must be created and driven from the main thread.
 */
class VoiceRecognizer(private val context: Context) {

    sealed interface Event {
        /** Mic is open; safe to tell the user to start talking. */
        data object Listening : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Failed(val reason: String) : Event
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Emits until [Event.Final] or [Event.Failed], then completes. Cancel the collector to abort. */
    fun listen(locale: Locale = Locale.getDefault()): Flow<Event> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(Event.Failed("No speech recognition service on this device"))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(Event.Listening) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.first()?.let { trySend(Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.first()
                if (text.isNullOrBlank()) trySend(Event.Failed("Nothing was recognised"))
                else trySend(Event.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(Event.Failed(describe(error)))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Ask for the on-device model when the recogniser has one; it falls back to the cloud
            // automatically, so this is a preference and not a guarantee.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)

        awaitClose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun Bundle.first(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_CLIENT -> "Recogniser client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Did not catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser is busy"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Recognition failed ($error)"
    }
}

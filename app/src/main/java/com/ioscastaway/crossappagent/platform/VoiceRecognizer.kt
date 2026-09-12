package com.ioscastaway.crossappagent.platform

import android.content.Context
import android.content.Intent
import android.os.Build
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
 * `RecognitionService` role does the work. On the test Galaxy that role is held by Google's
 * on-device recognizer, which has a small vocabulary: it will transcribe an unknown proper noun in
 * a partial result and then drop it from the final one. So [Event.Final] carries the N-best
 * alternatives and the longest partial as well, and the caller hands all of it to the model, which
 * is far better at guessing what a person meant than a decoder with a fixed word list.
 *
 * Must be created and driven from the main thread.
 */
class VoiceRecognizer(private val context: Context) {

    sealed interface Event {
        /** Mic is open; safe to tell the user to start talking. */
        data object Listening : Event
        data class Partial(val text: String) : Event

        /**
         * @param text the recognizer's top hypothesis
         * @param alternatives other hypotheses, best first, excluding [text]
         * @param longestPartial the longest interim transcript seen; often keeps a word the final lost
         */
        data class Final(
            val text: String,
            val alternatives: List<String>,
            val longestPartial: String?,
        ) : Event

        data class Failed(val reason: String) : Event
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Emits until [Event.Final] or [Event.Failed], then completes. Cancel the collector to abort.
     *
     * @param bias phrases to bias recognition toward (installed app names, mostly). Honoured by the
     * Google recognizer on Android 13+, ignored elsewhere.
     */
    fun listen(locale: Locale = Locale.getDefault(), bias: List<String> = emptyList()): Flow<Event> =
        callbackFlow {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                trySend(Event.Failed("No speech recognition service on this device"))
                close()
                return@callbackFlow
            }

            var longestPartial: String? = null
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { trySend(Event.Listening) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.all()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
                    if (p.length > (longestPartial?.length ?: 0)) longestPartial = p
                    trySend(Event.Partial(p))
                }

                override fun onResults(results: Bundle?) {
                    val all = results?.all().orEmpty().filter { it.isNotBlank() }
                    val top = all.firstOrNull()
                    if (top == null) {
                        trySend(Event.Failed("Nothing was recognised"))
                    } else {
                        trySend(
                            Event.Final(
                                text = top,
                                alternatives = all.drop(1).distinct().filter { it != top },
                                longestPartial = longestPartial?.takeIf { it != top },
                            )
                        )
                    }
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // No EXTRA_PREFER_OFFLINE: where a cloud recognizer exists it knows far more names.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && bias.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias.take(200)))
                }
            }
            recognizer.startListening(intent)

            awaitClose {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }

    private fun Bundle.all(): List<String> =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() } ?: emptyList()

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

    companion object {
        /**
         * Folds recognizer output into one task string for the model: the final transcript first,
         * then whatever else the recognizer heard, so a dropped proper noun can be recovered.
         */
        fun taskText(final: Event.Final): String = buildString {
            append(final.text)
            val extra = buildList {
                final.longestPartial?.let { add("earlier partial transcript: \"$it\"") }
                if (final.alternatives.isNotEmpty()) {
                    add("alternative transcripts: " + final.alternatives.joinToString(" | ") { "\"$it\"" })
                }
            }
            if (extra.isNotEmpty()) {
                append("\n\n(Speech-recognition notes — ")
                append(extra.joinToString("; "))
                append(". The recognizer runs on-device with a small vocabulary and tends to drop names ")
                append("and titles it does not know from the final transcript. If a partial or alternative ")
                append("contains a word the final one lacks, assume the user said it and use it, e.g. as the ")
                append("search term.)")
            }
        }
    }
}

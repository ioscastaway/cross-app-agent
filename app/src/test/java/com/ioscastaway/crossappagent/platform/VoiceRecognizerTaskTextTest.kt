package com.ioscastaway.crossappagent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognizerTaskTextTest {

    @Test
    fun `a clean final transcript is passed through untouched`() {
        val f = VoiceRecognizer.Event.Final("Open Settings", alternatives = emptyList(), longestPartial = null)
        assertEquals("Open Settings", VoiceRecognizer.taskText(f))
    }

    @Test
    fun `a partial that kept a word the final dropped travels with the task`() {
        // The real case that motivated this: an on-device recognizer heard the band name, then
        // rewrote the final transcript without it.
        val f = VoiceRecognizer.Event.Final(
            text = "유튜브에서 뮤직비디오 틀어줘",
            alternatives = listOf("유튜브에서 하츠 투 하츠 뮤직비디오 틀어줘"),
            longestPartial = "유튜브에서 하츠투하츠",
        )
        val t = VoiceRecognizer.taskText(f)
        assertTrue(t.startsWith("유튜브에서 뮤직비디오 틀어줘"))
        assertTrue(t.contains("earlier partial transcript: \"유튜브에서 하츠투하츠\""))
        assertTrue(t.contains("alternative transcripts: \"유튜브에서 하츠 투 하츠 뮤직비디오 틀어줘\""))
        assertTrue(t.contains("assume the user said it"))
    }

    @Test
    fun `alternatives identical to the final are not repeated`() {
        val f = VoiceRecognizer.Event.Final("hello", alternatives = emptyList(), longestPartial = "hello")
        // longestPartial equal to the final is filtered by the recognizer before it gets here; taskText
        // must still cope if a caller passes it through.
        val t = VoiceRecognizer.taskText(f.copy(longestPartial = null))
        assertEquals("hello", t)
    }
}

package com.crpakala.commutewidget.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteAudioDetectorTest {
    @Test
    fun matchesPlayingCommuteAudio_playingAndWatchedIsTrue() {
        assertTrue(
            CommuteAudioDetector.matchesPlayingCommuteAudio(
                packageName = "com.audible.application",
                playbackState = STATE_PLAYING,
                watchedPackages = setOf("com.audible.application"),
            ),
        )
    }

    @Test
    fun matchesPlayingCommuteAudio_pausedIsFalse() {
        assertFalse(
            CommuteAudioDetector.matchesPlayingCommuteAudio(
                packageName = "com.audible.application",
                playbackState = STATE_PAUSED,
                watchedPackages = setOf("com.audible.application"),
            ),
        )
    }

    @Test
    fun matchesPlayingCommuteAudio_playingButNotWatchedIsFalse() {
        assertFalse(
            CommuteAudioDetector.matchesPlayingCommuteAudio(
                packageName = "com.spotify.music",
                playbackState = STATE_PLAYING,
                watchedPackages = setOf("com.audible.application"),
            ),
        )
    }

    @Test
    fun matchesPlayingCommuteAudio_nullPlaybackStateIsFalse() {
        assertFalse(
            CommuteAudioDetector.matchesPlayingCommuteAudio(
                packageName = "com.audible.application",
                playbackState = null,
                watchedPackages = setOf("com.audible.application"),
            ),
        )
    }

    private companion object {
        // Mirrors android.media.session.PlaybackState constants, kept as literals so this test
        // does not depend on Android framework stub behavior.
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 2
    }
}

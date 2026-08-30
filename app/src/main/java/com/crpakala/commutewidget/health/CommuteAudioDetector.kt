package com.crpakala.commutewidget.health

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

/**
 * "Is a commute-audio app (e.g. Audible) playing right now" via [MediaSessionManager].
 * Requires the notification-listener access token in [HealthNotificationListener]; degrades to
 * false/empty on [SecurityException] (access not granted) or any other failure.
 */
object CommuteAudioDetector {

    // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is not exposed by the current SDK stubs;
    // this is the same key documented for the fallback check on all platform versions we target.
    private const val ENABLED_NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"

    /** True when this app holds notification-listener access, the gate for [getActiveSessions]. */
    fun hasNotificationAccess(context: Context): Boolean = runCatching {
        val listener = ComponentName(context, HealthNotificationListener::class.java)
        val grantedViaManager = (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.isNotificationListenerAccessGranted(listener)
            ?: false
        if (grantedViaManager) return@runCatching true

        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS_SETTING,
        )
        enabledListeners?.split(":")?.any { it == listener.flattenToString() } ?: false
    }.getOrDefault(false)

    /** Packages from [watchedPackages] currently in [PlaybackState.STATE_PLAYING]. Empty on any failure. */
    fun playingCommuteAudioPackages(context: Context, watchedPackages: Set<String>): Set<String> = runCatching {
        if (watchedPackages.isEmpty()) return@runCatching emptySet()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return@runCatching emptySet()
        val listener = ComponentName(context, HealthNotificationListener::class.java)
        manager.getActiveSessions(listener)
            .filter { isPlayingWatchedPackage(it, watchedPackages) }
            .map { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /** True if any watched package is currently playing audio. */
    fun isCommuteAudioPlaying(context: Context, watchedPackages: Set<String>): Boolean =
        playingCommuteAudioPackages(context, watchedPackages).isNotEmpty()

    private fun isPlayingWatchedPackage(controller: MediaController, watchedPackages: Set<String>): Boolean =
        matchesPlayingCommuteAudio(controller.packageName, controller.playbackState?.state, watchedPackages)

    /** Pure predicate: is this package one we watch, and is it actually in the playing state. */
    internal fun matchesPlayingCommuteAudio(
        packageName: String,
        playbackState: Int?,
        watchedPackages: Set<String>,
    ): Boolean = playbackState == PlaybackState.STATE_PLAYING && packageName in watchedPackages
}

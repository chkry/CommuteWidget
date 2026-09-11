package com.crpakala.commutewidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.eventIdentityKey
import com.crpakala.commutewidget.engine.CommuteRefresher
import com.crpakala.commutewidget.engine.RefreshTrigger
import com.crpakala.commutewidget.engine.applyReached
import com.crpakala.commutewidget.engine.applyRideTap
import com.crpakala.commutewidget.schedule.EventLeaveByScheduler
import java.time.LocalDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal val rideDirectionKey = ActionParameters.Key<String>("ride_direction")

internal fun rideTapAction(direction: Direction): Action =
    actionRunCallback<RideAction>(actionParametersOf(rideDirectionKey to direction.name))

internal fun reachedTapAction(direction: Direction): Action =
    actionRunCallback<ReachedAction>(actionParametersOf(rideDirectionKey to direction.name))

internal val eventStartKey = ActionParameters.Key<Long>("event_start")
internal val eventTitleKey = ActionParameters.Key<String>("event_title")

/** Reached pill on a routed calendar event's map: [startEpochMillis] and [title] identify the instance to close. */
internal fun eventReachedTapAction(startEpochMillis: Long, title: String): Action =
    actionRunCallback<EventReachedAction>(
        actionParametersOf(eventStartKey to startEpochMillis, eventTitleKey to title),
    )

/** The tapped pill's direction, or null when the parameter is missing or not a known direction. */
private fun ActionParameters.rideDirectionOrNull(): Direction? {
    val raw = this[rideDirectionKey] ?: return null
    return enumValues<Direction>().firstOrNull { it.name == raw }
}

/**
 * Starts (or resumes) a ride. The state write is a cheap, idempotent local edit, and the actual
 * route and map fetch happens in [CommuteRefresher.refreshNow], not here. The cooldown is
 * bypassed only when the tap actually changed the phase, because the pre-fetch render has hidden
 * the Ride pill and a cooldown-skipped refresh would strand the widget without a route. A
 * repeated tap that changes nothing takes the ordinary cooldown instead.
 */
class RideAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val direction = parameters.rideDirectionOrNull() ?: return
        val today = LocalDate.now().toString()
        val changed = withContext(NonCancellable) {
            SettingsRepository.get(context).updateRideState { applyRideTap(it, today, direction) }
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = changed)
    }
}

/**
 * Ends a ride. The state write is a cheap, idempotent local edit, and the card render comes from
 * [CommuteRefresher.refreshNow], not here. The cooldown is bypassed only when the tap actually
 * changed the phase, because the pre-fetch render has hidden the Reached pill and a
 * cooldown-skipped refresh would strand the widget on the stale route map. A repeated tap that
 * changes nothing takes the ordinary cooldown instead.
 */
class ReachedAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val direction = parameters.rideDirectionOrNull() ?: return
        val today = LocalDate.now().toString()
        val changed = withContext(NonCancellable) {
            SettingsRepository.get(context).updateRideState { applyReached(it, today, direction) }
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = changed)
    }
}

/**
 * Closes one calendar event instance for good: its [eventIdentityKey] joins the closed set, which
 * every calendar selector filters out before it picks, so the refresh below moves the widget on to
 * the next event, the flight-preview row, or the airport card. The pending leave-by wake-up is
 * cancelled here rather than left to the refresh, because the notification is scheduled for this
 * event and firing it after the user has said they arrived is exactly the noise the pill removes.
 * The cooldown is always bypassed: the pre-refresh render still shows the closed event's map.
 */
class EventReachedAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val startEpochMillis = parameters[eventStartKey] ?: return
        val title = parameters[eventTitleKey] ?: return
        withContext(NonCancellable) {
            SettingsRepository.get(context).updateClosedEventKeys { it + eventIdentityKey(startEpochMillis, title) }
            EventLeaveByScheduler.cancel(context)
        }
        CommuteRefresher.refreshNow(context, RefreshTrigger.TAP, bypassCooldown = true)
    }
}

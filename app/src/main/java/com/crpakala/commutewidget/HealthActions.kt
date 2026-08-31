package com.crpakala.commutewidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingWorkPolicy
import com.crpakala.commutewidget.data.CustomPillOccurrence
import com.crpakala.commutewidget.data.HealthNudgeKind
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.health.NudgeCandidate
import com.crpakala.commutewidget.engine.health.NudgeKind
import com.crpakala.commutewidget.health.HealthConnectFacade
import com.crpakala.commutewidget.schedule.HealthBoundaryScheduler
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal val healthSupplementKindKey = ActionParameters.Key<String>("health_supplement_kind")
internal val healthFocusGapStartMinuteKey = ActionParameters.Key<Int>("health_focus_gap_start_minute")
internal val healthInfoKindKey = ActionParameters.Key<String>("health_info_kind")
internal val customPillIdKey = ActionParameters.Key<String>("custom_pill_id")
internal val customPillSlotMinuteKey = ActionParameters.Key<Int>("custom_pill_slot_minute")

internal const val INFO_KIND_SLEEP = "SLEEP"
internal const val INFO_KIND_MORNING_LIGHT = "MORNING_LIGHT"

internal fun healthNudgeClickAction(candidate: NudgeCandidate): Action? = when (candidate.kind) {
    NudgeKind.SUPPLEMENT_MORNING -> actionRunCallback<SupplementTakenAction>(
        actionParametersOf(healthSupplementKindKey to SUPPLEMENT_KIND_MORNING),
    )
    NudgeKind.SUPPLEMENT_PROTEIN -> actionRunCallback<SupplementTakenAction>(
        actionParametersOf(healthSupplementKindKey to SUPPLEMENT_KIND_PROTEIN),
    )
    NudgeKind.WATER -> actionRunCallback<WaterTapAction>()
    NudgeKind.WALK -> actionRunCallback<WalkDismissAction>()
    NudgeKind.FOCUS_GAP -> actionRunCallback<FocusGapDismissAction>(
        actionParametersOf(healthFocusGapStartMinuteKey to candidate.startMinuteOfDay),
    )
    // Reachable only as event-map pills - the selector keeps these kinds line-only elsewhere,
    // and lines render without a click target.
    NudgeKind.SLEEP_ESTIMATE -> actionRunCallback<HealthInfoDismissAction>(
        actionParametersOf(healthInfoKindKey to INFO_KIND_SLEEP),
    )
    NudgeKind.MORNING_LIGHT -> actionRunCallback<HealthInfoDismissAction>(
        actionParametersOf(healthInfoKindKey to INFO_KIND_MORNING_LIGHT),
    )
    NudgeKind.CAFFEINE_CUTOFF -> null
    // Sprint 3: the tap writes its timestamp and immediately recomputes health fields locally,
    // which is what makes the tapped pill vanish on the next settled render (there is no
    // day-state dismissal flag for either kind - see filterHealthNudgesAgainstDayState).
    NudgeKind.SLEEP_TO_BED -> actionRunCallback<ToBedTapAction>()
    NudgeKind.SLEEP_WOKE_UP -> actionRunCallback<WokeUpTapAction>()
}

/** Custom pill reminders always have a tap target - no line-only surface, no non-clickable kind. */
internal fun customPillTapAction(occurrence: CustomPillOccurrence): Action = actionRunCallback<CustomPillTakenAction>(
    actionParametersOf(
        customPillIdKey to occurrence.pillId,
        customPillSlotMinuteKey to occurrence.slotMinuteOfDay,
    ),
)

class SupplementTakenAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val kind = parameters[healthSupplementKindKey] ?: return
        if (kind != SUPPLEMENT_KIND_MORNING && kind != SUPPLEMENT_KIND_PROTEIN) return
        withContext(NonCancellable) {
            val now = ZonedDateTime.now()
            val repo = SettingsRepository.get(context)
            repo.updateHealthDayState { current ->
                applySupplementTaken(
                    state = current,
                    todayIsoDate = LocalDate.now().toString(),
                    kind = kind,
                    takenMinuteOfDay = now.hour * 60 + now.minute,
                )
            }
            CommuteWidget().updateAll(context)
        }
    }
}

class WaterTapAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        withContext(NonCancellable) {
            val nowEpochMillis = System.currentTimeMillis()
            val wrote = HealthConnectFacade.writeHydration(
                context,
                WATER_TAP_VOLUME_ML,
                nowEpochMillis,
            )
            if (!wrote) return@withContext
            val now = ZonedDateTime.now()
            val repo = SettingsRepository.get(context)
            repo.updateHealthDayState { current ->
                applyWaterTap(
                    state = current,
                    todayIsoDate = LocalDate.now().toString(),
                    tapMinuteOfDay = now.hour * 60 + now.minute,
                )
            }
            CommuteWidget().updateAll(context)
        }
    }
}

class WalkDismissAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        withContext(NonCancellable) {
            val repo = SettingsRepository.get(context)
            repo.updateHealthDayState { current ->
                applyWalkDismissed(
                    state = current,
                    todayIsoDate = LocalDate.now().toString(),
                )
            }
            CommuteWidget().updateAll(context)
        }
    }
}

/**
 * Sprint 4 review finding 2: the tap itself stays CHEAP and bounded (Glance cancels action
 * coroutines after roughly 10 seconds) - it records the timestamp, strips the tapped kind from
 * the stored snapshot's nudges so the pill vanishes on the immediate re-render, and hands the
 * heavy full health recompute (calendar + Health Connect + UsageStats reads) to an immediate
 * one-shot run of the health boundary chain. REPLACE is the correct policy here: the tap action
 * is an external caller of the chain, and the worker's own self-reschedule (APPEND_OR_REPLACE)
 * re-establishes the future boundary - which also restores the safety-net wake the tap's domain
 * suppression removes from the candidate list. Re-tapping is harmless: the latest timestamp
 * wins, and the pill is already stripped.
 */
private suspend fun recordSleepTap(
    context: Context,
    strippedKind: HealthNudgeKind,
    persistTap: suspend (SettingsRepository) -> Unit,
) {
    withContext(NonCancellable) {
        val repo = SettingsRepository.get(context)
        persistTap(repo)
        repo.snapshot()?.let { snapshot ->
            repo.saveSnapshot(snapshot.copy(healthNudges = withoutNudgeKind(snapshot.healthNudges, strippedKind)))
        }
        runCatching { CommuteWidget().updateAll(context) }
        runCatching {
            HealthBoundaryScheduler.scheduleAt(context, ZonedDateTime.now(), ExistingWorkPolicy.REPLACE)
        }
    }
}

/**
 * Records the To Bed tap timestamp; the immediate boundary-worker run then recomputes health
 * fields with the tap as an anchor. Candidate suppression comes from
 * [SettingsRepository.lastToBedTapEpochMillis] at computation time - there is no day-state
 * dismissal flag; the snapshot strip in [recordSleepTap] only bridges the render gap until that
 * recompute lands.
 */
class ToBedTapAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        recordSleepTap(context, HealthNudgeKind.SLEEP_TO_BED) { repo ->
            repo.setLastToBedTapEpochMillis(System.currentTimeMillis())
        }
    }
}

/**
 * Records the Woke Up tap timestamp. This tap FINALIZES the night's sleep estimate: the
 * immediate boundary-worker run anchors the estimate to the unlock preceding the tap and the
 * freeze rule locks it in - off the Glance action budget, with the chain's own reschedule as
 * the safety net.
 */
class WokeUpTapAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        recordSleepTap(context, HealthNudgeKind.SLEEP_WOKE_UP) { repo ->
            repo.setLastWokeUpTapEpochMillis(System.currentTimeMillis())
        }
    }
}

class FocusGapDismissAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val gapStartMinute = parameters[healthFocusGapStartMinuteKey] ?: return
        withContext(NonCancellable) {
            val repo = SettingsRepository.get(context)
            repo.updateHealthDayState { current ->
                applyFocusGapDismissed(
                    state = current,
                    todayIsoDate = LocalDate.now().toString(),
                    gapStartMinute = gapStartMinute,
                )
            }
            CommuteWidget().updateAll(context)
        }
    }
}

class CustomPillTakenAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val pillId = parameters[customPillIdKey] ?: return
        val slotMinuteOfDay = parameters[customPillSlotMinuteKey] ?: return
        withContext(NonCancellable) {
            val repo = SettingsRepository.get(context)
            repo.updateHealthDayState { current ->
                applyCustomPillTaken(
                    state = current,
                    todayIsoDate = LocalDate.now().toString(),
                    pillId = pillId,
                    slotMinuteOfDay = slotMinuteOfDay,
                )
            }
            CommuteWidget().updateAll(context)
        }
    }
}

class HealthInfoDismissAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val kind = parameters[healthInfoKindKey] ?: return
        if (kind != INFO_KIND_SLEEP && kind != INFO_KIND_MORNING_LIGHT) return
        withContext(NonCancellable) {
            val repo = SettingsRepository.get(context)
            repo.updateHealthDayState { current ->
                val today = LocalDate.now().toString()
                when (kind) {
                    INFO_KIND_SLEEP -> applySleepPillDismissed(current, today)
                    else -> applyMorningLightDismissed(current, today)
                }
            }
            CommuteWidget().updateAll(context)
        }
    }
}

package com.crpakala.commutewidget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.updateAll
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.engine.health.NudgeCandidate
import com.crpakala.commutewidget.engine.health.NudgeKind
import com.crpakala.commutewidget.health.HealthConnectFacade
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal val healthSupplementKindKey = ActionParameters.Key<String>("health_supplement_kind")
internal val healthFocusGapStartMinuteKey = ActionParameters.Key<Int>("health_focus_gap_start_minute")
internal val healthInfoKindKey = ActionParameters.Key<String>("health_info_kind")

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
}

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

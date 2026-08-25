package com.crpakala.commutewidget

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.crpakala.commutewidget.schedule.CommuteScheduler
import com.crpakala.commutewidget.ui.CommuteWidgetApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommuteScheduler.ensureScheduledAsync(this)
        enableEdgeToEdge()
        setContent {
            var screen by remember { mutableStateOf(Screen.SETTINGS) }
            BackHandler(enabled = screen == Screen.STATS) { screen = Screen.SETTINGS }
            CommuteWidgetApp(
                showStats = screen == Screen.STATS,
                onViewStats = { screen = Screen.STATS },
                onBackToSettings = { screen = Screen.SETTINGS },
            )
        }
    }

    private enum class Screen { SETTINGS, STATS }
}

package com.crpakala.commutewidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crpakala.commutewidget.schedule.CommuteScheduler
import com.crpakala.commutewidget.ui.CommuteWidgetApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommuteScheduler.ensureScheduledAsync(this)
        enableEdgeToEdge()
        setContent {
            CommuteWidgetApp()
        }
    }
}

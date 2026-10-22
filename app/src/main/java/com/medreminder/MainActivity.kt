package com.medreminder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.medreminder.push.AlertTarget
import com.medreminder.ui.AppRoot

class MainActivity : ComponentActivity() {
    /** Set when the activity was opened from a family alert notification. */
    private var alert by mutableStateOf<AlertTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        alert = AlertTarget.from(intent)
        setContent {
            AppRoot(graph = graph, alert = alert, onAlertHandled = { alert = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AlertTarget.from(intent)?.let { alert = it }
    }
}

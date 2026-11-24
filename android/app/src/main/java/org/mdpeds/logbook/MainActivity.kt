package org.mdpeds.logbook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.mdpeds.logbook.ui.nav.AppRoot
import org.mdpeds.logbook.ui.nav.LaunchRequest

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Deep links (auth callback) and notification taps waiting to be consumed by the UI. */
    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            AppRoot(launchRequests = launchRequests, onConsumed = { launchRequests.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        when {
            data != null && data.scheme == "mdpeds" && data.host == "auth-callback" -> launchRequests.value = LaunchRequest.AuthCallback(data.toString())
            intent?.getStringExtra(EXTRA_OPEN) == "notifications" -> launchRequests.value = LaunchRequest.OpenNotifications
        }
    }

    companion object {
        const val EXTRA_OPEN = "org.mdpeds.logbook.OPEN"
    }
}

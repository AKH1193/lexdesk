package com.akh.lexdesk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.akh.lexdesk.ui.ChatScreen
import com.akh.lexdesk.ui.SettingsScreen
import com.akh.lexdesk.ui.theme.LexDeskTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            LexDeskTheme {
                var showSettings by rememberSaveable { mutableStateOf(vm.needsSetup()) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(vm, onBack = { showSettings = false })
                } else {
                    ChatScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Receives a document or text shared from another app. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) {
            vm.addAttachment(uri)
        } else {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.input = it }
        }
    }
}

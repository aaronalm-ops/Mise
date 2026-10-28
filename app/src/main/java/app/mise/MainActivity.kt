package app.mise

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mise.data.Prefs
import app.mise.sync.SyncEngine
import app.mise.ui.MiseRoot
import app.mise.ui.MiseTheme

class MainActivity : ComponentActivity() {
    /** Where a shortcut or the reminder asked to go: "list", "plan", "plan-next", "new-recipe". */
    private var open by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SyncEngine.init(this)
        if (savedInstanceState == null) open = intent?.getStringExtra(Reminder.OPEN_EXTRA)
        setContent {
            val dark = when (Prefs.theme) {
                Prefs.Theme.LIGHT -> false
                Prefs.Theme.DARK -> true
                Prefs.Theme.SYSTEM -> isSystemInDarkTheme()
            }
            // Status and navigation bar icons follow Mise's own light or dark choice.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MiseTheme(dark = dark) {
                MiseRoot(open = open, onOpened = { open = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Reminder.OPEN_EXTRA)?.let { open = it }
    }

    // Sync runs while Mise is on screen: every few seconds and straight after any edit, and
    // sends anything waiting as Mise goes to the background.
    override fun onStart() {
        super.onStart()
        SyncEngine.start()
    }

    override fun onStop() {
        SyncEngine.stop()
        super.onStop()
    }
}

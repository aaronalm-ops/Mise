package app.mise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mise.sync.SyncEngine
import app.mise.ui.MiseRoot
import app.mise.ui.MiseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge; status and navigation bar icons follow the phone's light or dark mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SyncEngine.init(this)
        setContent {
            MiseTheme {
                MiseRoot()
            }
        }
    }

    // Sync runs while Mise is on screen: every few seconds and straight after any edit.
    override fun onStart() {
        super.onStart()
        SyncEngine.start()
    }

    override fun onStop() {
        SyncEngine.stop()
        super.onStop()
    }
}

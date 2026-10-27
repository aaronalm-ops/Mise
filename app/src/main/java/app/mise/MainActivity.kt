package app.mise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mise.ui.MiseRoot
import app.mise.ui.MiseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge; status and navigation bar icons follow the phone's light or dark mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MiseTheme {
                MiseRoot()
            }
        }
    }
}

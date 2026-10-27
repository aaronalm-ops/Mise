package app.mise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun MiseRoot() {
    val c = Mise.colours
    var dest by rememberSaveable { mutableStateOf(Dest.Plan) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.base)) {
        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(onBack = { showSettings = false })
        } else {
            // Back from another tab returns to Plan before leaving the app.
            BackHandler(enabled = dest != Dest.Plan) { dest = Dest.Plan }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val open = { showSettings = true }
                    when (dest) {
                        Dest.Plan -> PlanScreen(open)
                        Dest.Recipes -> RecipesScreen(open)
                        Dest.List -> ListScreen(open)
                        Dest.Pantry -> PantryScreen(open)
                    }
                }
                MiseNavBar(current = dest, onSelect = { dest = it }, onAdd = { showAdd = true })
            }
        }
        if (showAdd) AddSheet(onDismiss = { showAdd = false })
    }
}

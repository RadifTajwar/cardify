package com.cardify

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Api.init(applicationContext)
        setContent { CardifyTheme { if (Api.token == null) AuthScreen() else App() } }
    }
}

@Composable
private fun CardifyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        // Android 12+: colors follow the user's wallpaper.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFA5B4FC))
        else -> lightColorScheme(primary = Color(0xFF4F46E5))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private sealed interface Screen {
    data object Home : Screen
    data class Detail(val card: Card) : Screen
    data class Edit(val card: Card) : Screen // blank id = new card
}

@Composable
private fun App() {
    var cards by remember { mutableStateOf(emptyList<Card>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Kept here rather than in HomeScreen, so opening a card and coming back keeps your tab and search.
    var publicTab by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val refresh: () -> Unit = {
        scope.launch {
            loading = true
            error = null
            io { Api.list() }
                .onSuccess { cards = it.byName() }
                .onFailure { error = "${it.message ?: "Can't reach the server"}\n(${BuildConfig.API_URL})" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val logout: () -> Unit = {
        scope.launch {
            io {
                // Cached photos belong to this account, so don't leave them for the next one on this phone.
                SingletonImageLoader.get(context).run {
                    memoryCache?.clear()
                    diskCache?.clear()
                }
                Api.logout() // the login screen takes over from here
            }
        }
    }

    // Up one level: Edit → Detail (Home for a new card) → Home.
    val up: () -> Unit = {
        val editing = (screen as? Screen.Edit)?.card
        screen = if (editing == null || editing.id.isEmpty()) Screen.Home else Screen.Detail(editing)
    }
    BackHandler(enabled = screen != Screen.Home, onBack = up)

    AnimatedContent(targetState = screen, label = "screen") { s ->
        when (s) {
            Screen.Home -> HomeScreen(
                cards = cards,
                loading = loading,
                error = error,
                onRefresh = refresh,
                publicTab = publicTab,
                onTab = { publicTab = it },
                query = query,
                onQuery = { query = it },
                onOpen = { screen = Screen.Detail(it) },
                onAdd = { screen = Screen.Edit(Card()) },
                onLogout = logout,
            )
            is Screen.Detail -> DetailScreen(
                card = s.card,
                onBack = up,
                onEdit = { screen = Screen.Edit(s.card) },
                onDeleted = {
                    cards = cards.filter { it.id != s.card.id }
                    screen = Screen.Home
                },
            )
            is Screen.Edit -> EditScreen(
                initial = s.card,
                onBack = up,
                onSaved = { saved ->
                    cards = (cards.filter { it.id != saved.id } + saved).byName()
                    screen = Screen.Detail(saved)
                },
            )
        }
    }
}

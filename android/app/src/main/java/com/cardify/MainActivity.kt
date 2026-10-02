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
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CardifyTheme { App() } }
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
    data class Detail(val id: String) : Screen
    data class Edit(val card: Card) : Screen // blank id = new card
}

@Composable
private fun App() {
    var cards by remember { mutableStateOf(emptyList<Card>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val scope = rememberCoroutineScope()

    val refresh: () -> Unit = {
        scope.launch {
            loading = true
            error = null
            io { Api.list() }
                .onSuccess { cards = it }
                .onFailure { error = "${it.message ?: "Can't reach the server"}\n(${BuildConfig.API_URL})" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // Up one level: Edit → Detail (Home for a new card) → Home.
    val up: () -> Unit = {
        val editing = (screen as? Screen.Edit)?.card?.id.orEmpty()
        screen = if (editing.isEmpty()) Screen.Home else Screen.Detail(editing)
    }
    BackHandler(enabled = screen != Screen.Home, onBack = up)

    AnimatedContent(targetState = screen, label = "screen") { s ->
        when (s) {
            Screen.Home -> HomeScreen(
                cards = cards,
                loading = loading,
                error = error,
                onRefresh = refresh,
                onOpen = { screen = Screen.Detail(it.id) },
                onAdd = { screen = Screen.Edit(Card()) },
            )
            is Screen.Detail -> cards.find { it.id == s.id }?.let { card ->
                DetailScreen(
                    card = card,
                    onBack = up,
                    onEdit = { screen = Screen.Edit(card) },
                    onDeleted = {
                        cards = cards - card
                        screen = Screen.Home
                    },
                )
            }
            is Screen.Edit -> EditScreen(
                initial = s.card,
                onBack = up,
                onSaved = { saved ->
                    cards = (cards.filter { it.id != saved.id } + saved).sortedBy { it.displayName.lowercase() }
                    screen = Screen.Detail(saved.id)
                },
            )
        }
    }
}

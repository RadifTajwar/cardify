package com.cardify

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    cards: List<Card>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    publicTab: Boolean,
    onTab: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Card) -> Unit,
    onAdd: () -> Unit,
    onLogout: () -> Unit,
) {
    // ponytail: all your cards are in memory, so searching them is a plain filter; page it server-side past a few thousand cards.
    val mineShown = remember(cards, query) { cards.filter { it.matches(query.trim()) } }

    // Public cards live on the server: search as you type, once typing pauses.
    var found by remember { mutableStateOf<List<Card>?>(null) } // null until the first search answers
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    if (publicTab) {
        LaunchedEffect(query, reload) {
            delay(300)
            searching = true
            io { Api.searchPublic(query.trim()) }
                .onSuccess {
                    found = it
                    searchError = null
                }
                .onFailure { searchError = it.message ?: "Can't reach the server" }
            searching = false
        }
    }

    val shown = if (publicTab) found.orEmpty() else mineShown
    val busy = if (publicTab) found == null || searching else loading
    val shownError = if (publicTab) searchError else error
    val retry: () -> Unit = if (publicTab) ({ reload++ }) else onRefresh
    val count = when {
        publicTab && query.isBlank() -> "Newest public cards"
        publicTab -> plural(shown.size, "result")
        query.isBlank() -> plural(cards.size, "card")
        else -> "${shown.size} of ${plural(cards.size, "card")}"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(ScanIcon, null) },
                text = { Text("Scan card", style = MaterialTheme.typography.titleSmall) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = busy && shown.isNotEmpty(),
            onRefresh = retry,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 104.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    Api.userName.substringBefore(' ').ifBlank { "Your cards" },
                                    style = MaterialTheme.typography.headlineMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AccountMenu(onLogout)
                        }
                        Spacer(Modifier.height(18.dp))
                        SearchField(query, onQuery, if (publicTab) "Search everyone's public cards" else "Search name, company, phone…")
                        Spacer(Modifier.height(14.dp))
                        TabSwitch(publicTab, onTab)
                        if (shown.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (shownError != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 6.dp)) {
                            Text(shownError, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = retry) { Text("Try again") }
                        }
                    }
                }
                if (busy && shown.isEmpty()) {
                    items(6) { SkeletonTile() }
                }
                items(shown, key = { it.id }) { card -> CardTile(card, publicTab) { onOpen(card) } }
                if (!busy && shown.isEmpty() && shownError == null) item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        query.isNotBlank() -> EmptyState(
                            Icons.Default.Search,
                            "No matches",
                            "Nothing in ${if (publicTab) "public cards" else "your cards"} matches “${query.trim()}”.",
                        )
                        publicTab -> EmptyState(PublicIcon, "No public cards yet", "When people make a card public, you'll find it here.")
                        else -> EmptyState(ScanIcon, "Your wallet is empty", "Tap Scan card and Cardify reads the details for you.")
                    }
                }
            }
        }
    }
}

private fun greeting() = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

@Composable
private fun CardTile(card: Card, publicTab: Boolean, onClick: () -> Unit) {
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Box {
            CardFace(
                card,
                Modifier
                    .sharedCard(card.id)
                    .fillMaxWidth()
                    .aspectRatio(CARD_RATIO)
                    .shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = glow, spotColor = glow),
            )
            if (!publicTab && card.isPublic) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(PublicIcon, "Public", Modifier.size(15.dp), tint = Color.White) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(card.displayName, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = if (publicTab) (if (card.mine) "Shared by you" else "Shared by ${card.ownerName}") else card.subtitle
        if (line.isNotEmpty()) {
            Text(
                line,
                Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A pulsing placeholder tile while cards load. */
@Composable
private fun SkeletonTile() {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "pulse",
    )
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.alpha(pulse)) {
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).clip(RoundedCornerShape(16.dp)).background(fill))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(CircleShape).background(fill))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(CircleShape).background(fill))
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 2.dp) {
        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Clear search") }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/** "My cards" | "Public", with a pill that slides to the chosen side. */
@Composable
private fun TabSwitch(publicTab: Boolean, onTab: (Boolean) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
    ) {
        val half = maxWidth / 2
        val offset by animateDpAsState(if (publicTab) half else 0.dp, label = "tab")
        Box(
            Modifier
                .offset(x = offset)
                .width(half)
                .fillMaxHeight()
                .shadow(2.dp, CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape),
        )
        Row(Modifier.fillMaxSize()) {
            listOf("My cards" to false, "Public" to true).forEach { (label, isPublic) ->
                val selected = publicTab == isPublic
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = selected, role = Role.Tab) { onTab(isPublic) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountMenu(onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Avatar(Api.userName, Modifier.clip(CircleShape).clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(Api.userName)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(Api.userName, style = MaterialTheme.typography.titleSmall)
                    Text(Api.userEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Log out") },
                leadingIcon = { Icon(LogoutIcon, null) },
                onClick = {
                    open = false
                    onLogout()
                },
            )
        }
    }
}

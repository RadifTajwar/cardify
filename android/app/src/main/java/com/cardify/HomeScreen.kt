package com.cardify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cardify", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    AccountMenu(onLogout)
                }
                PrimaryTabRow(selectedTabIndex = if (publicTab) 1 else 0) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Tab(selected = !publicTab, onClick = { onTab(false) }, text = { Text("My cards") }, unselectedContentColor = muted)
                    Tab(selected = publicTab, onClick = { onTab(true) }, text = { Text("Public") }, unselectedContentColor = muted)
                }
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (publicTab) "Search everyone's public cards…" else "Search name, company, phone…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Clear search") }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Scan card") },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = busy && shown.isNotEmpty(),
            onRefresh = retry,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (shownError != null) item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
                            Text(shownError, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = retry) { Text("Retry") }
                        }
                    }
                }
                items(shown, key = { it.id }) { card ->
                    // On the Public tab, say whose card it is; on yours, mark the ones you've made public.
                    val line = if (publicTab) listOf(card.subtitle, if (card.mine) "by you" else "by ${card.ownerName}") else listOf(card.subtitle)
                    ListItem(
                        headlineContent = { Text(card.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = line.filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = {
                            CardFace(card, Modifier.width(84.dp).aspectRatio(CARD_RATIO).clip(RoundedCornerShape(8.dp)))
                        },
                        trailingContent = if (!publicTab && card.isPublic) {
                            { Icon(WebIcon, "Public", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable { onOpen(card) },
                    )
                }
                if (shown.isEmpty() && shownError == null) item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            busy -> CircularProgressIndicator()
                            query.isNotBlank() -> Text("No ${if (publicTab) "public " else ""}cards match “${query.trim()}”")
                            publicTab -> Text("No public cards yet.\nMake one of yours public to share it.", textAlign = TextAlign.Center)
                            else -> Text("No cards yet.\nTap Scan card to add your first one.", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountMenu(onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.AccountCircle, "Account") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(Api.userName, style = MaterialTheme.typography.titleSmall)
                Text(Api.userEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenuItem(
                text = { Text("Log out") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                onClick = {
                    open = false
                    onLogout()
                },
            )
        }
    }
}

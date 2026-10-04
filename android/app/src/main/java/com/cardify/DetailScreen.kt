package com.cardify

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.Intents.Insert
import android.provider.ContactsContract.RawContacts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(card: Card, onBack: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (card.mine) { // someone else's public card is read-only
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            val shape = RoundedCornerShape(16.dp)
            CardFace(
                card,
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(CARD_RATIO).shadow(6.dp, shape).clip(shape),
                large = true,
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                Text(card.displayName, style = MaterialTheme.typography.headlineSmall)
                if (card.subtitle.isNotEmpty()) {
                    Text(card.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(if (card.isPublic) WebIcon else Icons.Default.Lock, null, Modifier.size(16.dp), tint = muted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            !card.mine -> "Public card shared by ${card.ownerName}"
                            card.isPublic -> "Public: everyone on Cardify can find it"
                            else -> "Private: only you can see it"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                    )
                }
            }

            card.phones.forEach { phone ->
                InfoRow(Icons.Default.Call, phone) { context.startSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
            }
            if (card.email.isNotBlank()) {
                InfoRow(Icons.Default.Email, card.email) {
                    context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", card.email, null)))
                }
            }
            if (card.website.isNotBlank()) {
                val url = if (card.website.startsWith("http", ignoreCase = true)) card.website else "https://${card.website}"
                InfoRow(WebIcon, card.website) { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            if (card.address.isNotBlank()) {
                InfoRow(Icons.Default.Place, card.address) {
                    context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(card.address))))
                }
            }
            if (card.notes.isNotBlank()) InfoRow(Icons.Default.Info, card.notes)

            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { context.startSafely(card.contactIntent()) }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Person, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save contact")
                }
                FilledTonalButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, card.shareText())
                        context.startSafely(Intent.createChooser(send, null))
                    },
                    Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this card?") },
            text = { Text("${card.displayName} will be removed for good, photo included.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        io { Api.delete(card.id) }.onSuccess { onDeleted() }.onFailure { context.toast(it.message) }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Opens the phone's "new contact" screen prefilled; no contacts permission needed. */
private fun Card.contactIntent() = Intent(Insert.ACTION).apply {
    type = RawContacts.CONTENT_TYPE
    putExtra(Insert.NAME, name)
    putExtra(Insert.COMPANY, company)
    putExtra(Insert.JOB_TITLE, title)
    putExtra(Insert.EMAIL, email)
    putExtra(Insert.POSTAL, address)
    putExtra(Insert.NOTES, listOf(website, notes).filter { it.isNotBlank() }.joinToString("\n"))
    listOf(Insert.PHONE, Insert.SECONDARY_PHONE, Insert.TERTIARY_PHONE).zip(phones).forEach { (key, number) -> putExtra(key, number) }
}

private fun Card.shareText() =
    (listOf(displayName, subtitle) + phones + listOf(email, website, address)).filter { it.isNotBlank() }.joinToString("\n")

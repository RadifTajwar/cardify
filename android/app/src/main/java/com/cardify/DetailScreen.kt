package com.cardify

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.Intents.Insert
import android.provider.ContactsContract.RawContacts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
    val website = if (card.website.startsWith("http", ignoreCase = true)) card.website else "https://${card.website}"
    val dial = { phone: String -> context.startSafely(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
    val mail = { context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", card.email, null))) }
    val browse = { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(website))) }
    val map = { context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(card.address)))) }
    val share = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, card.shareText())
        context.startSafely(Intent.createChooser(send, null))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                actions = {
                    if (card.mine) { // someone else's public card is read-only
                        RoundButton(Icons.Default.Edit, "Edit", onEdit)
                        RoundButton(Icons.Default.Delete, "Delete") { confirmDelete = true }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            CardFace(
                card,
                Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .sharedCard(card.id)
                    .fillMaxWidth()
                    .aspectRatio(CARD_RATIO)
                    .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = glow, spotColor = glow),
                large = true,
            )
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
                Text(card.displayName, style = MaterialTheme.typography.headlineMedium)
                if (card.subtitle.isNotEmpty()) {
                    Text(card.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                VisibilityPill(card)
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                card.phones.firstOrNull()?.let { phone -> QuickAction(Icons.Default.Call, "Call") { dial(phone) } }
                if (card.email.isNotBlank()) QuickAction(Icons.Default.Email, "Email", mail)
                if (card.website.isNotBlank()) QuickAction(WebIcon, "Website", browse)
                if (card.address.isNotBlank()) QuickAction(Icons.Default.Place, "Map", map)
                QuickAction(Icons.Default.Share, "Share", share)
            }

            val rows = buildList<@Composable () -> Unit> {
                card.phones.forEachIndexed { i, phone ->
                    add { InfoRow(Icons.Default.Call, if (card.phones.size > 1) "Phone ${i + 1}" else "Phone", phone) { dial(phone) } }
                }
                if (card.email.isNotBlank()) add { InfoRow(Icons.Default.Email, "Email", card.email, mail) }
                if (card.website.isNotBlank()) add { InfoRow(WebIcon, "Website", card.website, browse) }
                if (card.address.isNotBlank()) add { InfoRow(Icons.Default.Place, "Address", card.address, map) }
                if (card.notes.isNotBlank()) add { InfoRow(NotesIcon, "Notes", card.notes) }
            }
            if (rows.isNotEmpty()) {
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            row()
                        }
                    }
                }
                Text(
                    "Long-press a detail to copy it.",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { context.startSafely(card.contactIntent()) },
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(54.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Person, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Save to contacts", style = MaterialTheme.typography.titleSmall)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete this card?") },
            text = { Text("${card.displayName} will be removed for good, photo included.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            io { Api.delete(card.id) }.onSuccess { onDeleted() }.onFailure { context.toast(it.message) }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) { Icon(icon, label) }
}

@Composable
private fun VisibilityPill(card: Card) {
    val public = card.isPublic
    val content = if (public) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = if (public) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (public) PublicIcon else Icons.Default.Lock, null, Modifier.size(16.dp), tint = content)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    !card.mine -> "Public · shared by ${card.ownerName}"
                    public -> "Public · anyone on Cardify can find it"
                    else -> "Private · only you can see it"
                },
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(icon, label)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One detail: tap to act on it (call, email…), long-press to copy it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onClick?.invoke() ?: context.copy(value) }, onLongClick = { context.copy(value) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun Context.copy(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cardify", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied") // newer Android shows its own notice
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

package com.cardify

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

// Google's scanner UI: finds the card's edges, crops and flattens it, and can import from the gallery.
private val scannerOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setPageLimit(1)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(initial: Card, onBack: () -> Unit, onSaved: (Card) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf(initial) }
    var photo by remember { mutableStateOf<Uri?>(null) } // a fresh scan, uploaded on save
    var busy by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = card != initial || photo != null

    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        photo = uri
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: IOException) {
            context.toast("Couldn't open the scan: ${e.message}")
            return@rememberLauncherForActivityResult
        }
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        ocr.process(image)
            .addOnSuccessListener { card = card.fillBlanks(parseCardText(it.text)) }
            .addOnFailureListener { context.toast("Couldn't read the card's text: ${it.message}") }
            .addOnCompleteListener { ocr.close() }
    }
    val scan: () -> Unit = {
        if (activity != null) {
            GmsDocumentScanning.getClient(scannerOptions).getStartScanIntent(activity)
                .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { context.toast("Scanner unavailable: ${it.message}") }
        }
    }
    // A new card opens straight into the scanner; back out of it to type the details by hand.
    LaunchedEffect(Unit) { if (initial.id.isEmpty()) scan() }

    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(onBack = leave)

    val save: () -> Unit = save@{
        if (busy) return@save // a quick double tap would otherwise POST the card twice
        busy = true
        scope.launch {
            val saved = io { Api.save(card) }.onFailure { context.toast(it.message) }.getOrNull()
            if (saved != null) {
                card = saved // has an id now, so retrying after a failed upload updates instead of duplicating
                val pending = photo
                val done = if (pending == null) saved else io { Api.putImage(saved.id, context.jpeg(pending)) }
                    .onFailure { context.toast("Details saved, but the photo upload failed: ${it.message}") }
                    .getOrNull()
                if (done != null) onSaved(done)
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.id.isEmpty()) "New card" else "Edit card") },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.Default.Close, "Close") } },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = save, enabled = card.name.isNotBlank() || card.company.isNotBlank()) { Text("Save") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(CARD_RATIO)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = scan),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    photo != null -> AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    card.hasImage -> CardFace(card, Modifier.fillMaxSize())
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Add, null)
                        Text("Tap to scan the card")
                    }
                }
            }
            if (photo != null || card.hasImage) {
                Text(
                    "Tap the photo to rescan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = card.isPublic, role = Role.Switch) { card = card.copy(isPublic = it) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Public", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (card.isPublic) "Everyone on Cardify can find this card" else "Only you can see this card",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = card.isPublic, onCheckedChange = null)
            }
            Field("Name", card.name, KeyboardCapitalization.Words) { card = card.copy(name = it) }
            Field("Job title", card.title, KeyboardCapitalization.Words) { card = card.copy(title = it) }
            Field("Company", card.company, KeyboardCapitalization.Words) { card = card.copy(company = it) }
            Field("Phone", card.phone, type = KeyboardType.Phone, multiLine = true, hint = "One number per line, or comma-separated") {
                card = card.copy(phone = it)
            }
            Field("Email", card.email, type = KeyboardType.Email) { card = card.copy(email = it) }
            Field("Website", card.website, type = KeyboardType.Uri) { card = card.copy(website = it) }
            Field("Address", card.address, KeyboardCapitalization.Words, multiLine = true) { card = card.copy(address = it) }
            Field("Notes", card.notes, KeyboardCapitalization.Sentences, multiLine = true) { card = card.copy(notes = it) }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = onBack) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    type: KeyboardType = KeyboardType.Text,
    multiLine: Boolean = false,
    hint: String? = null,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    supportingText = hint?.let { { Text(it) } },
    singleLine = !multiLine,
    keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = type),
)

/** Re-encodes the scan at 1600px on its longest side, so each photo is a few hundred KB. */
private fun Context.jpeg(uri: Uri): ByteArray {
    val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: throw IOException("Couldn't read the scanned photo")
    val scale = minOf(1f, 1600f / maxOf(src.width, src.height))
    val bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true)
    } else {
        src
    }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

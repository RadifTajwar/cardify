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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (initial.id.isEmpty()) "New card" else "Edit card", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.Default.Close, "Close") } },
                actions = {
                    Button(
                        onClick = save,
                        enabled = !busy && (card.name.isNotBlank() || card.company.isNotBlank()),
                        modifier = Modifier.padding(end = 12.dp),
                        shape = CircleShape,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PhotoBox(photo, card, scan)
            Section("Who can see it") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceTile(!card.isPublic, Icons.Default.Lock, "Private", "Only you", Modifier.weight(1f)) { card = card.copy(isPublic = false) }
                    ChoiceTile(card.isPublic, PublicIcon, "Public", "Anyone on Cardify", Modifier.weight(1f)) { card = card.copy(isPublic = true) }
                }
            }
            Section("Person") {
                Field("Name", card.name, Icons.Default.Person, KeyboardCapitalization.Words) { card = card.copy(name = it) }
                Field("Job title", card.title, WorkIcon, KeyboardCapitalization.Words) { card = card.copy(title = it) }
                Field("Company", card.company, BusinessIcon, KeyboardCapitalization.Words) { card = card.copy(company = it) }
            }
            Section("Contact") {
                Field("Phone", card.phone, Icons.Default.Call, type = KeyboardType.Phone, multiLine = true, hint = "One number per line, or comma-separated") {
                    card = card.copy(phone = it)
                }
                Field("Email", card.email, Icons.Default.Email, type = KeyboardType.Email) { card = card.copy(email = it) }
                Field("Website", card.website, WebIcon, type = KeyboardType.Uri) { card = card.copy(website = it) }
                Field("Address", card.address, Icons.Default.Place, KeyboardCapitalization.Words, multiLine = true) { card = card.copy(address = it) }
            }
            Section("Notes") {
                Field("Where you met, what you talked about…", card.notes, NotesIcon, KeyboardCapitalization.Sentences, multiLine = true) {
                    card = card.copy(notes = it)
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("What you've typed or scanned on this card won't be saved.") },
            confirmButton = { TextButton(onClick = onBack) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** The scan: a dashed drop zone before there is one, then the photo with a hint to rescan. */
@Composable
private fun PhotoBox(photo: Uri?, card: Card, scan: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val outline = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                if (photo == null && !card.hasImage) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = outline,
                            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                            cornerRadius = CornerRadius(24.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = scan),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            card.hasImage -> CardFace(card, Modifier.fillMaxSize())
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(CameraIcon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                Text("Scan the card", style = MaterialTheme.typography.titleMedium)
                Text("Cardify reads the details for you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (photo != null || card.hasImage) {
            Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ScanIcon, null, Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Rescan", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun ChoiceTile(selected: Boolean, icon: ImageVector, title: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerLowest)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(icon, null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    icon: ImageVector,
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
    leadingIcon = { Icon(icon, null) },
    supportingText = hint?.let { { Text(it) } },
    singleLine = !multiLine,
    shape = RoundedCornerShape(16.dp),
    keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = type),
    colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    ),
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

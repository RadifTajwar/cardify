package com.cardify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade

const val CARD_RATIO = 1.75f // a standard 3.5" × 2" visiting card

val Card.subtitle get() = listOf(title, if (name.isBlank()) "" else company).filter { it.isNotBlank() }.joinToString(" · ")

/** The card's photo, or a gradient face with the initials when it has none. */
@Composable
fun CardFace(card: Card, modifier: Modifier = Modifier, large: Boolean = false) {
    if (card.hasImage) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(Api.imageUrl(card))
                .httpHeaders(NetworkHeaders.Builder().set("Authorization", Api.auth).build())
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        val hue = (card.displayName.hashCode() and 0x7fffffff) % 360f
        Box(
            modifier.background(Brush.linearGradient(listOf(Color.hsv(hue, 0.5f, 0.85f), Color.hsv((hue + 40) % 360, 0.7f, 0.5f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                card.displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

fun Context.toast(message: String?) = Toast.makeText(this, message ?: "Something went wrong", Toast.LENGTH_LONG).show()

fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast("No app on this phone can open that")
    }
}

/** Material's "language" globe; the core icon set has no web icon. */
val WebIcon = ImageVector.Builder("Web", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z"
        ),
        fill = SolidColor(Color.Black),
    )
    .build()

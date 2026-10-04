package com.cardify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

const val CARD_RATIO = 1.75f // a standard 3.5" × 2" visiting card

val Card.subtitle get() = listOf(title, if (name.isBlank()) "" else company).filter { it.isNotBlank() }.joinToString(" · ")

fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

// Gradients for cards without a photo, picked by name so a card keeps its colors.
private val faceGradients = listOf(
    0xFF6366F1 to 0xFF8B5CF6, // indigo → violet
    0xFF0EA5E9 to 0xFF6366F1, // sky → indigo
    0xFF10B981 to 0xFF0D9488, // emerald → teal
    0xFFF59E0B to 0xFFEF4444, // amber → red
    0xFFEC4899 to 0xFF8B5CF6, // pink → violet
    0xFF14B8A6 to 0xFF3B82F6, // teal → blue
    0xFFF97316 to 0xFFDB2777, // orange → pink
    0xFF3B82F6 to 0xFF1E3A8A, // blue → navy
)

/** The card's photo, or a gradient face with the initials when it has none. */
@Composable
fun CardFace(card: Card, modifier: Modifier = Modifier, large: Boolean = false) {
    if (card.hasImage) {
        // Straight from Cloudinary: the signed link is the permission, so the login token isn't sent along.
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(card.photoUrl).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else {
        val (from, to) = faceGradients[(card.displayName.hashCode() and 0x7fffffff) % faceGradients.size]
        Box(
            modifier
                .background(Brush.linearGradient(listOf(Color(from), Color(to))))
                .drawBehind {
                    // Two soft circles, so a card without a photo still looks like a designed card.
                    drawCircle(Color.White.copy(alpha = 0.16f), radius = size.height * 0.8f, center = Offset(size.width * 0.95f, 0f))
                    drawCircle(Color.White.copy(alpha = 0.08f), radius = size.height * 0.55f, center = Offset(size.width * 0.05f, size.height))
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(card.displayName),
                color = Color.White,
                style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/** A round badge with someone's initials on the brand gradient. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
        Text(initials(name).ifEmpty { "?" }, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** A friendly placeholder for an empty list. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// The screen transition's shared-element scopes, so a tapped card can grow into the details screen.
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }
val LocalScreenTransition = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Marks a card's face as the same element on every screen it appears on. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(id: String): Modifier {
    val shared = LocalSharedTransition.current
    val screen = LocalScreenTransition.current
    if (shared == null || screen == null || id.isEmpty()) return this
    return with(shared) { this@sharedCard.sharedElement(rememberSharedContentState("card-$id"), screen) }
}

fun Context.toast(message: String?) = Toast.makeText(this, message ?: "Something went wrong", Toast.LENGTH_LONG).show()

fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast("No app on this phone can open that")
    }
}

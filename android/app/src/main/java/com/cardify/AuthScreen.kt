package com.cardify

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

private enum class Mode { LogIn, SignUp, Forgot, Reset }

/** Log in, sign up, or recover a password. Any of them sets [Api.token], and the app then shows your cards. */
@Composable
fun AuthScreen() {
    var mode by rememberSaveable { mutableStateOf(Mode.LogIn) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // unlike the rest, not kept in the saved activity state
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val googleReady = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()

    // White status and navigation bar icons on the dark gradient, put back when the screen goes.
    val view = LocalView.current
    DisposableEffect(activity) {
        val bars = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val light = bars?.run { isAppearanceLightStatusBars to isAppearanceLightNavigationBars }
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (bars != null && light != null) {
                bars.isAppearanceLightStatusBars = light.first
                bars.isAppearanceLightNavigationBars = light.second
            }
        }
    }

    fun go(to: Mode) {
        mode = to
        error = null
        password = ""
        code = ""
    }

    // Runs one request with the spinner on; its failure message shows in the form.
    fun run(block: () -> Unit, then: () -> Unit = {}) {
        busy = true
        error = null
        scope.launch {
            io(block).onSuccess { then() }.onFailure { error = it.message ?: "Can't reach the server" }
            busy = false
        }
    }

    val submit: () -> Unit = {
        val e = email.trim()
        when (mode) {
            Mode.LogIn -> run({ Api.login(e, password) })
            Mode.SignUp -> run({ Api.signup(name.trim(), e, password) })
            Mode.Forgot -> run({ Api.forgotPassword(e) }) { go(Mode.Reset) }
            Mode.Reset -> run({ Api.resetPassword(e, code, password) })
        }
    }
    val ready = !busy && when (mode) {
        Mode.LogIn -> email.isNotBlank() && password.isNotEmpty()
        Mode.SignUp -> name.isNotBlank() && email.isNotBlank() && password.isNotEmpty()
        Mode.Forgot -> email.isNotBlank()
        Mode.Reset -> code.length == 6 && password.isNotEmpty()
    }

    val google: () -> Unit = {
        if (activity != null) {
            busy = true
            error = null
            scope.launch {
                try {
                    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
                    val credential = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(option))).credential
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    io { Api.google(idToken) }.onFailure { error = it.message ?: "Google sign-in didn't work" }
                } catch (_: GetCredentialCancellationException) {
                    // closed Google's sheet: nothing to say
                } catch (_: NoCredentialException) {
                    error = "Add a Google account to this phone first, in Settings."
                } catch (e: GetCredentialException) {
                    error = e.message ?: "Google sign-in didn't work"
                }
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BrandGradient)) {
        Canvas(Modifier.fillMaxSize()) { // soft glows on the gradient
            drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.55f, center = Offset(size.width * 0.95f, size.height * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.06f), radius = size.width * 0.4f, center = Offset(0f, size.height * 0.3f))
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Image(BrandMark, null, Modifier.size(width = 36.dp, height = 24.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text("Cardify", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text(
                    "Every visiting card you collect, scanned and searchable.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                AnimatedContent(mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "auth") { m ->
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (m) {
                            Mode.LogIn, Mode.SignUp -> {
                                Heading(
                                    if (m == Mode.LogIn) "Welcome back" else "Create your account",
                                    if (m == Mode.LogIn) "Log in to your cards." else "It takes a few seconds, and it's free.",
                                )
                                if (googleReady) {
                                    GoogleButton(enabled = !busy, onClick = google)
                                    OrDivider()
                                }
                                if (m == Mode.SignUp) {
                                    AuthField(name, { name = it }, "Name", Icons.Default.Person, KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                                }
                                AuthField(email, { email = it }, "Email", Icons.Default.Email, KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                                PasswordField(password, { password = it }, if (m == Mode.SignUp) "At least 8 characters" else null, submit)
                                if (m == Mode.LogIn) {
                                    TextButton(onClick = { go(Mode.Forgot) }, Modifier.align(Alignment.End)) { Text("Forgot password?") }
                                }
                            }
                            Mode.Forgot -> {
                                Badge(LockResetIcon)
                                Heading("Forgot your password?", "Enter your email and we'll send you a 6-digit code to choose a new one.")
                                AuthField(
                                    email, { email = it }, "Email", Icons.Default.Email,
                                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done), onDone = submit,
                                )
                            }
                            Mode.Reset -> {
                                Badge(MailSentIcon)
                                Heading("Check your email", "If ${email.trim()} has a Cardify account, a 6-digit code is on its way. It works for 15 minutes.")
                                CodeField(code) { code = it }
                                PasswordField(password, { password = it }, "Your new password, at least 8 characters", submit, label = "New password")
                                TextButton(
                                    onClick = { run({ Api.forgotPassword(email.trim()) }) { context.toast("Asked for a new code; it can take a minute") } },
                                    enabled = !busy,
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) { Text("Didn't get it? Send it again") }
                            }
                        }
                        error?.let {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                                Text(it, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(
                            onClick = submit,
                            enabled = ready,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = CircleShape,
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text(
                                    when (m) {
                                        Mode.LogIn -> "Log in"
                                        Mode.SignUp -> "Create account"
                                        Mode.Forgot -> "Send code"
                                        Mode.Reset -> "Set new password"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val (prompt, action, target) = when (mode) {
                Mode.LogIn -> Triple("New to Cardify?", "Create an account", Mode.SignUp)
                Mode.SignUp -> Triple("Already have an account?", "Log in", Mode.LogIn)
                Mode.Forgot, Mode.Reset -> Triple("Remembered it?", "Back to log in", Mode.LogIn)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(prompt, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { go(target) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                    Text(action, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Badge(icon: ImageVector) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GoogleButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Image(GoogleLogo, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Continue with Google", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text("or with email", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboard: KeyboardOptions,
    onDone: () -> Unit = {},
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    leadingIcon = { Icon(icon, null) },
    singleLine = true,
    shape = RoundedCornerShape(16.dp),
    keyboardOptions = keyboard,
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
)

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, hint: String?, onDone: () -> Unit, label: String = "Password") {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) VisibilityOffIcon else VisibilityIcon, if (shown) "Hide password" else "Show password")
            }
        },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Six boxes for the emailed code; typing fills them left to right. */
@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = code,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        decorationBox = { inner ->
            Box {
                Box(Modifier.alpha(0f)) { inner() } // the real (invisible) field, for the cursor and keyboard
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val current = i == code.length
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(0.82f)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(14.dp))
                                .border(
                                    if (current) 2.dp else 1.dp,
                                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        },
    )
}

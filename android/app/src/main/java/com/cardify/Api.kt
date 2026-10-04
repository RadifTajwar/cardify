package com.cardify

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Card(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    val company: String = "",
    val phone: String = "", // several numbers separated by newlines or commas
    val email: String = "",
    val website: String = "",
    val address: String = "",
    val notes: String = "",
    val isPublic: Boolean = false, // public cards show up in every user's search; private ones only for you
    val photoUrl: String = "", // signed Cloudinary link, empty when the card has no photo
    val mine: Boolean = true, // false for someone else's public card, which is read-only
    val ownerName: String = "",
) {
    val displayName get() = name.ifBlank { company }
    val hasImage get() = photoUrl.isNotEmpty()
    val phones get() = phone.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun matches(query: String) =
        listOf(name, title, company, phone, email, website, address, notes).any { it.contains(query, ignoreCase = true) }

    /** Keeps what's already filled in and takes [other]'s value only where this card is blank. */
    fun fillBlanks(other: Card) = copy(
        name = name.ifBlank { other.name },
        title = title.ifBlank { other.title },
        company = company.ifBlank { other.company },
        phone = phone.ifBlank { other.phone },
        email = email.ifBlank { other.email },
        website = website.ifBlank { other.website },
        address = address.ifBlank { other.address },
        notes = notes.ifBlank { other.notes },
    )
}

fun List<Card>.byName() = sortedBy { it.displayName.lowercase() }

/** Runs blocking [block] on the IO pool. Failures come back as a Result; cancellation still propagates. */
suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

/** The Go server's REST API. Every call blocks, so wrap it in [io]. */
object Api {
    private val base = BuildConfig.API_URL.trimEnd('/')
    private lateinit var prefs: SharedPreferences

    /** The login token, or null when logged out; the app shows the login screen whenever it's null. */
    var token by mutableStateOf<String?>(null)
        private set
    val userName get() = prefs.getString("name", "").orEmpty()
    val userEmail get() = prefs.getString("email", "").orEmpty()

    /** Call once at startup. The login is kept in app-private storage, so you stay logged in. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        token = prefs.getString("token", null)
    }

    fun signup(name: String, email: String, password: String) =
        startSession("/api/auth/signup", JSONObject().put("name", name).put("email", email).put("password", password))

    fun login(email: String, password: String) =
        startSession("/api/auth/login", JSONObject().put("email", email).put("password", password))

    private fun startSession(path: String, body: JSONObject) {
        val res = JSONObject(call("POST", path, body.toString().toByteArray()))
        val user = res.getJSONObject("user")
        val t = res.getString("token")
        prefs.edit().putString("token", t).putString("name", user.optString("name")).putString("email", user.optString("email")).apply()
        token = t
    }

    /** Forgets the login on this phone, and on the server too when it's reachable. */
    fun logout() {
        runCatching { call("POST", "/api/auth/logout") }
        forget()
    }

    private fun forget() {
        prefs.edit().clear().apply()
        token = null
    }

    /** Your own cards, private and public. */
    fun list() = cards("/api/cards")

    /** Everyone's public cards matching [query], newest first; the newest of all when it's blank. */
    fun searchPublic(query: String) = cards("/api/cards/public?q=" + URLEncoder.encode(query, "UTF-8"))

    private fun cards(path: String): List<Card> {
        val a = JSONArray(call("GET", path))
        return List(a.length()) { a.getJSONObject(it).toCard() }
    }

    fun save(c: Card): Card {
        val body = JSONObject()
            .put("name", c.name).put("title", c.title).put("company", c.company).put("phone", c.phone)
            .put("email", c.email).put("website", c.website).put("address", c.address).put("notes", c.notes)
            .put("public", c.isPublic)
            .toString().toByteArray()
        val res = if (c.id.isEmpty()) call("POST", "/api/cards", body) else call("PUT", "/api/cards/${c.id}", body)
        return JSONObject(res).toCard()
    }

    fun putImage(id: String, jpeg: ByteArray) =
        JSONObject(call("PUT", "/api/cards/$id/image", jpeg, "image/jpeg")).toCard()

    fun delete(id: String) {
        call("DELETE", "/api/cards/$id")
    }

    private fun call(method: String, path: String, body: ByteArray? = null, type: String = "application/json"): String {
        val sent = token
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            if (sent != null) conn.setRequestProperty("Authorization", "Bearer $sent")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body) }
            }
            if (conn.responseCode !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText().trim() }
                // The server no longer knows this login (it expired or was logged out), so back to the login screen.
                if (conn.responseCode == 401 && sent != null && sent == token) forget()
                throw IOException(if (msg.isNullOrEmpty()) "Server error ${conn.responseCode}" else msg)
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.toCard() = Card(
        id = getString("id"),
        name = optString("name"),
        title = optString("title"),
        company = optString("company"),
        phone = optString("phone"),
        email = optString("email"),
        website = optString("website"),
        address = optString("address"),
        notes = optString("notes"),
        isPublic = optBoolean("public"),
        photoUrl = optString("photoUrl"),
        mine = optBoolean("mine"),
        ownerName = optString("ownerName"),
    )
}

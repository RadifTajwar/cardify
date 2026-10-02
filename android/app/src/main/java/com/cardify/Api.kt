package com.cardify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    val hasImage: Boolean = false,
    val updatedAt: String = "",
) {
    val displayName get() = name.ifBlank { company }
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

/** Runs blocking [block] on the IO pool. Failures come back as a Result; cancellation still propagates. */
suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

/** The Go server's REST API. Every call blocks, so wrap it in [io]. */
object Api {
    private val base = BuildConfig.API_URL.trimEnd('/')
    val auth = "Bearer ${BuildConfig.API_TOKEN}"

    // The version param changes whenever the photo does, so image caches never show a stale scan.
    fun imageUrl(c: Card) = "$base/api/cards/${c.id}/image?v=${c.updatedAt.filter(Char::isDigit)}"

    fun list(): List<Card> {
        val a = JSONArray(call("GET", "/api/cards"))
        return List(a.length()) { a.getJSONObject(it).toCard() }
    }

    fun save(c: Card): Card {
        val body = JSONObject()
            .put("name", c.name).put("title", c.title).put("company", c.company).put("phone", c.phone)
            .put("email", c.email).put("website", c.website).put("address", c.address).put("notes", c.notes)
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
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", auth)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body) }
            }
            if (conn.responseCode !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText().trim() }
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
        hasImage = optBoolean("hasImage"),
        updatedAt = optString("updatedAt"),
    )
}

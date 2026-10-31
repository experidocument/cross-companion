package com.crosscompanion.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var lastUrl: String?
        get() = sp.getString("last_url", null)
        set(value) = sp.edit { putString("last_url", value) }

    var onboardingSeen: Boolean
        get() = sp.getBoolean("onboarding_seen", false)
        set(value) = sp.edit { putBoolean("onboarding_seen", value) }

    var webhookUrl: String?
        get() = sp.getString("webhook_url", null)?.takeIf { it.isNotBlank() }
        set(value) = sp.edit { putString("webhook_url", value) }
}

object Http {
    private const val USER_AGENT = "CrossCompanion/1.0 (Android crossword companion app)"

    class HttpException(val code: Int) : IOException("HTTP $code")

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Apps Script web apps run the script on the POST and then answer with a 302 to
     * googleusercontent.com, where the script's output can be fetched with a GET. Returns
     * that output (or the direct response body), or "" if the redirect can't be read.
     */
    suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val location: String?
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..399) throw HttpException(code)
            if (code in 200..299) return@withContext conn.inputStream.bufferedReader().use { it.readText() }
            location = conn.getHeaderField("Location")
        } finally {
            conn.disconnect()
        }
        // The POST already ran the script; failing to read its reply isn't a sync failure.
        location?.let { runCatching { get(it) }.getOrNull() }.orEmpty()
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

fun Context.openExternal(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, url, Toast.LENGTH_LONG).show()
    }
}

fun Context.googleSearch(query: String) =
    openExternal("https://www.google.com/search?q=" + Http.encode(query))

fun Context.copyToClipboard(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun Context.shareText(subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, subject))
}

fun Context.toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

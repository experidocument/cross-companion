package com.crosscompanion.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class JournalEntry(
    val id: String = UUID.randomUUID().toString(),
    val term: String,
    val category: String,
    val trivia: String,
    val etymology: String? = null,
    val usage: String? = null,
    val source: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    /** True once this entry has been sent to the user's Google Doc. */
    val synced: Boolean = false,
) {
    val displayDate: String
        get() = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(dateAdded))

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("term", term)
        put("category", category)
        put("trivia", trivia)
        put("etymology", etymology ?: JSONObject.NULL)
        put("usage", usage ?: JSONObject.NULL)
        put("source", source ?: JSONObject.NULL)
        put("dateAdded", dateAdded)
        put("dateAddedIso", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateAdded)))
        put("synced", synced)
    }

    fun toMarkdown(): String = buildString {
        appendLine("### ${term.uppercase()}")
        appendLine("*$category* · added $displayDate")
        appendLine()
        appendLine(trivia)
        etymology?.let { appendLine(); appendLine("**Origin:** $it") }
        usage?.let { appendLine(); appendLine("**Usage:** _${it}_") }
        source?.let { appendLine(); appendLine("Source: $it") }
    }.trimEnd()

    companion object {
        fun fromJson(o: JSONObject) = JournalEntry(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            term = o.getString("term"),
            category = Categories.normalize(o.optString("category", Categories.GENERAL.label)),
            trivia = o.optString("trivia"),
            etymology = o.optStringOrNull("etymology"),
            usage = o.optStringOrNull("usage"),
            source = o.optStringOrNull("source"),
            dateAdded = o.optLong("dateAdded", System.currentTimeMillis()),
            synced = o.optBoolean("synced", false),
        )
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** Offline journal stored as a single JSON array in app-private storage. */
class Journal(context: Context) {
    private val file = File(context.filesDir, "journal.json")

    suspend fun all(): List<JournalEntry> = withContext(Dispatchers.IO) { read() }

    /** Returns false if an entry with the same term already exists. */
    suspend fun add(entry: JournalEntry): Boolean = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val entries = read()
            if (entries.any { it.term.equals(entry.term, ignoreCase = true) }) return@withContext false
            write(listOf(entry) + entries)
            true
        }
    }

    suspend fun findByTerm(term: String): JournalEntry? =
        all().firstOrNull { it.term.equals(term, ignoreCase = true) }

    suspend fun markSynced(ids: Collection<String>) = withContext(Dispatchers.IO) {
        synchronized(LOCK) { write(read().map { if (it.id in ids) it.copy(synced = true) else it }) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(LOCK) { write(read().filterNot { it.id == id }) }
    }

    private fun read(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { JournalEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<JournalEntry>) {
        val arr = JSONArray().apply { entries.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, "journal.json.tmp")
        tmp.writeText(arr.toString(2))
        tmp.renameTo(file)
    }

    companion object {
        private val LOCK = Any()

        /** Groups entries under their category headers, matching what the Doc webhook writes. */
        fun exportMarkdown(entries: List<JournalEntry>): String =
            entries.groupBy { it.category }.entries.joinToString("\n\n") { (category, group) ->
                "## $category\n\n" + group.joinToString("\n\n") { it.toMarkdown() }
            }
    }
}

object Webhook {
    /**
     * Sends entries for the Apps Script to file under their category headers. Throws if the
     * script reports a failure, so callers only mark entries synced after a real success.
     */
    suspend fun sendEntries(url: String, entries: List<JournalEntry>) {
        val body = JSONObject().apply {
            put("type", "entries")
            put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        }
        val response = Http.postJson(url, body.toString())
        val result = runCatching { JSONObject(response) }.getOrNull() ?: return
        if (!result.optBoolean("ok", true)) throw IllegalStateException(result.optString("error", "script error"))
    }

    fun isValidUrl(url: String) = url.startsWith("https://script.google.com/")
}

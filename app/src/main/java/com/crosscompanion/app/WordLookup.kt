package com.crosscompanion.app

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

data class LookupResult(
    val term: String,
    val category: String,
    val summary: String,
    val etymology: String?,
    val usage: String?,
    /** Encyclopedia article the summary's trivia came from. */
    val sourceUrl: String?,
    /** Dictionary page the definition came from. */
    val definitionSourceUrl: String?,
    /** Page the origin came from, when it isn't the dictionary page above. */
    val etymologySourceUrl: String?,
    /** A picture of the subject, when the encyclopedia article has one. */
    val imageUrl: String?,
    val related: List<String>,
) {
    fun toEntry(categoryLabel: String = category) = JournalEntry(
        term = term,
        category = categoryLabel,
        trivia = summary,
        etymology = etymology,
        usage = usage,
        source = sourceUrl,
        definitionSource = definitionSourceUrl,
        etymologySource = etymologySourceUrl,
        imageUrl = imageUrl,
    )
}

/** Thrown when none of the sources could be reached, as opposed to them having nothing to say. */
class LookupUnavailableException(cause: Throwable?) : IOException("No lookup source reachable", cause)

/** Encyclopedia + dictionary lookups against Wikipedia, Wiktionary and the Free Dictionary API. */
object WordLookup {

    private data class WikiSummary(
        val title: String,
        val description: String?,
        val extract: String,
        val url: String?,
        val imageUrl: String?,
    )

    private data class DictEntry(
        val definition: String?,
        val partOfSpeech: String?,
        val example: String?,
        val origin: String?,
        val sourceUrl: String?,
    )

    /** Redirect targets that are collections, whose summary describes the list rather than the term. */
    private val COLLECTION_TITLE = Regex("^(Glossary|List|Index|Outline|Timeline) of ", RegexOption.IGNORE_CASE)

    /** Collapses whitespace and drops punctuation other than hyphens, apostrophes and periods. */
    fun normalize(query: String): String =
        query.trim()
            .replace(Regex("[\\u2018\\u2019]"), "'")
            .replace(Regex("[^\\p{L}\\p{N}\\s'.\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Returns null when the sources were reached but none knew the term. Throws
     * [LookupUnavailableException] only if every source failed with a network error, so a single
     * slow or rate-limited source never turns into "check your connection".
     */
    suspend fun lookup(rawQuery: String): LookupResult? = coroutineScope {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return@coroutineScope null

        val calls = Calls()
        val wikiJob = async { calls.attempt { wikiSummary(query) } }
        val dictJob = async { calls.attempt { dictionary(query) } }
        val etymJob = async { calls.attempt { wiktionaryEtymology(query) } }

        var related = emptyList<String>()
        var wiki = wikiJob.await()?.let { summary ->
            if (COLLECTION_TITLE.containsMatchIn(summary.title)) {
                related = listOf(summary.title)
                null
            } else summary
        }
        if (wiki == null) {
            // Exact title missing or ambiguous: surface the closest articles instead of a blank card.
            val search = calls.attempt { wikiSearch(query) }
            val hits = search?.titles.orEmpty().filterNot { COLLECTION_TITLE.containsMatchIn(it) }
            // Full-text search happily returns articles that merely mention the words somewhere;
            // only adopt the top hit if its title shares a word with the query.
            val best = hits.firstOrNull()?.takeIf { sharesWord(it, query) }
            related = (related + hits.filterNot { it == best } + listOfNotNull(search?.suggestion)).distinct().take(5)
            wiki = best?.let { calls.attempt { wikiSummary(it) } }
        }
        val dict = dictJob.await()
        val wiktionaryOrigin = etymJob.await()
        calls.throwIfNothingReached()

        val etymology = dict?.origin ?: wiktionaryOrigin
        val etymologySource = when {
            dict?.origin != null -> null // same page as the definition
            wiktionaryOrigin != null -> "https://en.wiktionary.org/wiki/${Http.encode(query.lowercase().replace(' ', '_'))}#Etymology"
            else -> null
        }

        if (wiki == null && dict?.definition == null) {
            return@coroutineScope if (related.isEmpty()) null
            else LookupResult(query, Categories.GENERAL.label, "", null, null, null, null, null, null, related)
        }

        val summary = buildString {
            dict?.definition?.let { def ->
                dict.partOfSpeech?.let { append("($it) ") }
                append(def)
            }
            wiki?.let {
                if (isNotEmpty()) append("\n\n")
                append(it.extract)
            }
        }
        val displayTerm = if (wiki != null && dict?.definition == null) wiki.title else query

        LookupResult(
            term = displayTerm,
            category = Categories.detect(wiki?.description, wiki?.extract, hasDictionary = dict?.definition != null).label,
            summary = summary,
            etymology = etymology,
            usage = dict?.example,
            sourceUrl = wiki?.url,
            definitionSourceUrl = dict?.definition?.let { dict.sourceUrl },
            etymologySourceUrl = etymologySource,
            imageUrl = wiki?.imageUrl,
            related = related,
        )
    }

    private fun words(text: String) =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private fun sharesWord(title: String, query: String): Boolean {
        val q = words(query)
        return q.isEmpty() || words(title).any { it in q }
    }

    /** Counts which source calls got an answer (including "not found") and which hit network errors. */
    private class Calls {
        private val reached = AtomicInteger()
        private val failed = AtomicInteger()
        @Volatile private var lastError: Throwable? = null

        suspend fun <T> attempt(block: suspend () -> T?): T? = try {
            block().also { reached.incrementAndGet() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Http.HttpException) {
            // The server answered, just not usefully (rate limit, outage): not a connection problem.
            reached.incrementAndGet()
            null
        } catch (e: IOException) {
            failed.incrementAndGet()
            lastError = e
            null
        } catch (e: Exception) {
            // Malformed JSON and the like: the network worked.
            reached.incrementAndGet()
            null
        }

        fun throwIfNothingReached() {
            if (reached.get() == 0 && failed.get() > 0) throw LookupUnavailableException(lastError)
        }
    }

    private suspend fun wikiSummary(title: String): WikiSummary? {
        val path = Http.encode(title.replace(' ', '_'))
        val json = try {
            JSONObject(Http.get("https://en.wikipedia.org/api/rest_v1/page/summary/$path?redirect=true"))
        } catch (e: Http.HttpException) {
            if (e.code == 404) return null else throw e
        }
        if (json.optString("type") == "disambiguation") return null
        val extract = json.optString("extract").takeIf { it.isNotBlank() } ?: return null
        val urls = json.optJSONObject("content_urls")
        val url = urls?.optJSONObject("desktop")?.optStringOrNull("page")
            ?: urls?.optJSONObject("mobile")?.optStringOrNull("page")
        val image = json.optJSONObject("originalimage")?.optStringOrNull("source")
            ?: json.optJSONObject("thumbnail")?.optStringOrNull("source")
        return WikiSummary(json.optString("title", title), json.optStringOrNull("description"), extract, url, image)
    }

    private class SearchHits(val titles: List<String>, val suggestion: String?)

    private suspend fun wikiSearch(query: String): SearchHits {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=6&srsearch=" +
            Http.encode(query)
        val q = JSONObject(Http.get(url)).optJSONObject("query") ?: return SearchHits(emptyList(), null)
        val hits = q.optJSONArray("search") ?: JSONArray()
        val titles = (0 until hits.length()).map { hits.getJSONObject(it).getString("title") }
        // "Did you mean" spelling suggestion, e.g. srketa → sakata.
        val suggestion = q.optJSONObject("searchinfo")?.optStringOrNull("suggestion")
        return SearchHits(titles, suggestion)
    }

    private suspend fun dictionary(word: String): DictEntry? {
        val arr = try {
            JSONArray(Http.get("https://api.dictionaryapi.dev/api/v2/entries/en/" + Http.encode(word.lowercase())))
        } catch (e: Http.HttpException) {
            if (e.code == 404) return null else throw e
        }
        if (arr.length() == 0) return null
        val first = arr.getJSONObject(0)
        val origin = (0 until arr.length()).firstNotNullOfOrNull { arr.getJSONObject(it).optStringOrNull("origin") }
        val sourceUrl = first.optJSONArray("sourceUrls")?.optString(0)?.takeIf { it.startsWith("http") }

        var definition: String? = null
        var pos: String? = null
        var example: String? = null
        val meanings = first.optJSONArray("meanings") ?: JSONArray()
        for (i in 0 until meanings.length()) {
            val m = meanings.getJSONObject(i)
            val defs = m.optJSONArray("definitions") ?: continue
            for (j in 0 until defs.length()) {
                val d = defs.getJSONObject(j)
                if (definition == null) {
                    definition = d.optStringOrNull("definition")
                    pos = m.optStringOrNull("partOfSpeech")
                }
                if (example == null) example = d.optStringOrNull("example")
            }
            if (definition != null && example != null) break
        }
        return DictEntry(definition, pos, example, origin, sourceUrl)
    }

    /** Pulls the first English "Etymology" section out of Wiktionary's plain-text extract. */
    private suspend fun wiktionaryEtymology(word: String): String? {
        val url = "https://en.wiktionary.org/w/api.php?action=query&prop=extracts&explaintext=1" +
            "&exsectionformat=wiki&redirects=1&format=json&titles=" + Http.encode(word.lowercase())
        val pages = JSONObject(Http.get(url)).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: return null
        val text = page.optString("extract")
        val english = text.substringAfter("== English ==", missingDelimiterValue = "")
            .substringBefore("\n== ")
        if (english.isEmpty()) return null
        val match = Regex("=== Etymology[^=]*===\\s*\\n([\\s\\S]*?)(\\n==|$)").find(english) ?: return null
        return match.groupValues[1].trim()
            .lineSequence()
            .map { it.trim() }
            // Skip Wiktionary's maintenance notices, e.g. "(This etymology is missing or incomplete…".
            .firstOrNull { it.isNotBlank() && !it.startsWith("(This etymology") && !it.startsWith("This etymology") }
            ?.take(400)
            ?.takeIf { it.length > 8 }
    }
}

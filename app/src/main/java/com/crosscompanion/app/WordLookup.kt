package com.crosscompanion.app

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

data class LookupResult(
    val term: String,
    val category: String,
    val summary: String,
    val etymology: String?,
    val usage: String?,
    val sourceUrl: String?,
    val related: List<String>,
) {
    fun toEntry() = JournalEntry(
        term = term,
        category = category,
        trivia = summary,
        etymology = etymology,
        usage = usage,
        source = sourceUrl,
    )
}

/** Encyclopedia + dictionary lookups against Wikipedia, Wiktionary and the Free Dictionary API. */
object WordLookup {

    private data class WikiSummary(val title: String, val description: String?, val extract: String, val url: String?)
    private data class DictEntry(val definition: String?, val partOfSpeech: String?, val example: String?, val origin: String?)

    /** Collapses whitespace and drops punctuation other than hyphens, apostrophes and periods. */
    fun normalize(query: String): String =
        query.trim()
            .replace(Regex("[\\u2018\\u2019]"), "'")
            .replace(Regex("[^\\p{L}\\p{N}\\s'.\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Returns null when no source has anything to say. Network failures propagate. */
    suspend fun lookup(rawQuery: String): LookupResult? = coroutineScope {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return@coroutineScope null

        val wikiJob = async { runCatching { wikiSummary(query) }.getOrNull() }
        val dictJob = async { runCatching { dictionary(query) }.getOrNull() }
        val etymJob = async { runCatching { wiktionaryEtymology(query) }.getOrNull() }

        var wiki = wikiJob.await()
        var related = emptyList<String>()
        if (wiki == null) {
            // Exact title missing or ambiguous: surface the closest articles instead of a blank card.
            val hits = wikiSearch(query)
            related = hits.drop(1).take(5)
            wiki = hits.firstOrNull()?.let { runCatching { wikiSummary(it) }.getOrNull() }
        }
        val dict = dictJob.await()
        val etymology = dict?.origin ?: etymJob.await()

        if (wiki == null && dict?.definition == null) {
            return@coroutineScope if (related.isEmpty()) null
            else LookupResult(query, "General", "", null, null, null, related)
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
            category = categorize(wiki?.description, wiki?.extract, hasDictionary = dict?.definition != null),
            summary = summary,
            etymology = etymology,
            usage = dict?.example,
            sourceUrl = wiki?.url,
            related = related,
        )
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
        val url = json.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
        return WikiSummary(json.optString("title", title), json.optStringOrNull("description"), extract, url)
    }

    private suspend fun wikiSearch(query: String): List<String> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=6&srsearch=" +
            Http.encode(query)
        val hits = JSONObject(Http.get(url)).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).map { hits.getJSONObject(it).getString("title") }
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
        return DictEntry(definition, pos, example, origin)
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
            .lineSequence().firstOrNull { it.isNotBlank() }
            ?.take(400)
            ?.takeIf { it.length > 8 }
    }

    private val CATEGORIES = linkedMapOf(
        "Geography" to listOf("city", "country", "capital", "river", "mountain", "island", "lake", "state of",
            "province", "region", "town", "village", "ocean", "sea", "desert", "county", "peninsula", "volcano"),
        "Food" to listOf("dish", "food", "cuisine", "fruit", "vegetable", "beverage", "drink", "cheese", "sauce",
            "dessert", "bread", "pastry", "spice", "herb", "cocktail", "wine", "soup"),
        "Arts" to listOf("film", "novel", "singer", "band", "album", "painter", "actor", "actress", "song",
            "opera", "poet", "artist", "musician", "composer", "television", "play by", "sculptor", "rapper",
            "character", "comic", "video game", "author", "writer", "ballet"),
        "Sports" to listOf("footballer", "basketball", "baseball", "athlete", "sport", "tennis", "olympic",
            "cricketer", "team", "golfer", "boxer", "hockey"),
        "Science" to listOf("chemical", "element", "species", "genus", "physics", "biology", "mineral", "disease",
            "planet", "star", "compound", "mammal", "bird", "fish", "insect", "plant", "unit of", "molecule",
            "astronomy", "mathemat", "anatomy", "reptile"),
        "History" to listOf("war", "empire", "dynasty", "battle", "emperor", "king of", "queen of", "pharaoh",
            "ancient", "revolution", "medieval"),
        "Mythology & Religion" to listOf("god", "goddess", "myth", "deity", "religio", "biblical", "saint", "legend"),
        "Language" to listOf("language", "alphabet", "letter", "abbreviation", "prefix", "suffix", "idiom", "phrase"),
        "People" to listOf("politician", "businessman", "businesswoman", "philosopher", "scientist", "inventor",
            "president", "prime minister", "born"),
    )

    private fun categorize(description: String?, extract: String?, hasDictionary: Boolean): String {
        // The short description ("Capital of France") is the strongest signal, so check it first.
        listOfNotNull(description, extract?.take(300)).forEach { text ->
            val lower = text.lowercase()
            CATEGORIES.forEach { (name, words) ->
                if (words.any { Regex("\\b${Regex.escape(it)}").containsMatchIn(lower) }) return name
            }
        }
        return if (hasDictionary) "Vocabulary" else "General"
    }
}

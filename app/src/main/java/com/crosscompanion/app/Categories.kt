package com.crosscompanion.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Category(val emoji: String, val name: String, val custom: Boolean = false) {
    /** What entries store and the Google Doc uses as the section header, e.g. "🎬 Film & TV". */
    val label: String get() = "$emoji $name"
}

object Categories {
    val FILM = Category("🎬", "Film & TV")
    val MUSIC = Category("🎵", "Music")
    val SPORTS = Category("⚽", "Sports")
    val HISTORY = Category("🏛️", "History")
    val LITERATURE = Category("📜", "Literature")
    val ARTS = Category("🎨", "Arts")
    val GEOGRAPHY = Category("🌍", "Geography")
    val FOOD = Category("🍜", "Food & Drink")
    val SCIENCE = Category("🧪", "Science")
    val VOCABULARY = Category("📖", "Vocabulary")
    val GENERAL = Category("💡", "General")

    val BUILT_IN = listOf(FILM, MUSIC, SPORTS, HISTORY, LITERATURE, ARTS, GEOGRAPHY, FOOD, SCIENCE, VOCABULARY, GENERAL)

    const val NAME_MAX = 10

    val EMOJI_PRESETS = listOf(
        "🏷️", "⭐", "🧩", "🧠", "🐾", "🌸", "🚀", "🎮", "❤️", "🏠",
        "✈️", "💼", "🎭", "🐉", "🌊", "⚡", "🍀", "👑", "🔤", "🎲",
    )

    /** Labels written by earlier versions, mapped onto the current buckets. */
    private val LEGACY = mapOf(
        "Film & TV" to FILM, "Music" to MUSIC, "Sports" to SPORTS, "History" to HISTORY,
        "Literature" to LITERATURE, "Arts" to ARTS, "Geography" to GEOGRAPHY, "Food" to FOOD,
        "Food & Drink" to FOOD, "Science" to SCIENCE, "Vocabulary" to VOCABULARY, "Language" to VOCABULARY,
        "Mythology & Religion" to HISTORY, "People" to GENERAL, "General" to GENERAL,
        "General Trivia" to GENERAL, "💡 General Trivia" to GENERAL,
    )

    fun normalize(label: String): String = LEGACY[label.trim()]?.label ?: label

    fun all(context: Context): List<Category> = BUILT_IN + custom(context)

    // region Custom categories

    private fun prefs(context: Context) = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    fun custom(context: Context): List<Category> {
        val raw = prefs(context).getString("custom_categories", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Category(o.getString("emoji"), o.getString("name"), custom = true)
            }
        }.getOrDefault(emptyList())
    }

    /** Returns false when a category with the same name already exists. */
    fun addCustom(context: Context, category: Category): Boolean {
        val existing = all(context)
        if (existing.any { it.name.equals(category.name, ignoreCase = true) }) return false
        save(context, custom(context) + category.copy(custom = true))
        return true
    }

    fun removeCustom(context: Context, category: Category) =
        save(context, custom(context).filterNot { it.name.equals(category.name, ignoreCase = true) })

    private fun save(context: Context, list: List<Category>) {
        val arr = JSONArray().apply {
            list.forEach { put(JSONObject().put("emoji", it.emoji).put("name", it.name)) }
        }
        prefs(context).edit { putString("custom_categories", arr.toString()) }
    }

    // endregion

    // region Detection

    // Checked in order, so more specific buckets (a film "character", a "star" athlete) win
    // over looser ones further down.
    private val RULES: List<Pair<Category, List<String>>> = listOf(
        FILM to listOf("film", "movie", "actor", "actress", "director", "filmmaker", "screenwriter", "cinema",
            "television", "tv series", "tv show", "sitcom", "animated series", "hollywood",
            "fictional character", "character in", "soap opera", "miniseries", "documentary", "anime"),
        MUSIC to listOf("singer", "songwriter", "band", "album", "song", "musician", "composer", "rapper",
            "opera", "guitarist", "pianist", "drummer", "violinist", "conductor", "symphony", "musical instrument",
            "record label", "jazz", "hip hop", "rock group", "pop group", "orchestra", "single by",
            "woodwind", "brass instrument", "string instrument", "stringed instrument", "percussion instrument"),
        LITERATURE to listOf("novel", "novelist", "poet", "poem", "poetry", "author", "writer", "playwright",
            "play by", "book", "literary", "epic poem", "fairy tale", "short story", "comic", "manga", "essayist",
            "tragedy", "comedy by", "written by", "sonnet", "saga"),
        SPORTS to listOf("footballer", "football", "soccer", "basketball", "baseball", "tennis", "olympic",
            "athlete", "cricketer", "cricket", "golfer", "golf", "boxer", "boxing", "hockey", "sport",
            "racing driver", "swimmer", "wrestler", "league", "stadium", "quarterback", "pitcher", "marathon"),
        ARTS to listOf("painter", "painting", "sculptor", "sculpture", "artist", "art movement", "ballet",
            "dancer", "dance", "architect", "architecture", "photographer", "illustrator", "museum"),
        FOOD to listOf("dish", "food", "cuisine", "fruit", "vegetable", "beverage", "drink", "cheese", "sauce",
            "dessert", "bread", "pastry", "spice", "herb", "cocktail", "wine", "beer", "soup", "liqueur", "cereal"),
        GEOGRAPHY to listOf("city", "country", "capital", "river", "mountain", "island", "lake", "state of",
            "province", "region", "town", "village", "ocean", "sea", "desert", "county", "peninsula", "volcano",
            "municipality", "archipelago", "strait", "bay"),
        SCIENCE to listOf("chemical", "element", "species", "genus", "physics", "biology", "mineral", "disease",
            "planet", "star", "compound", "mammal", "bird", "fish", "insect", "plant", "molecule", "astronomy",
            "mathematics", "mathematician", "mathematical", "anatomy", "reptile", "extinct", "protein", "enzyme", "unit of", "theorem", "galaxy"),
        HISTORY to listOf("war", "empire", "dynasty", "battle", "emperor", "king", "queen", "pharaoh", "ancient",
            "revolution", "medieval", "president", "prime minister", "politician", "statesman", "military leader",
            "explorer", "monarch", "ruler", "god", "goddess", "myth", "deity", "historical"),
    )

    /** A Wikipedia description with a life span, e.g. "(1564–1616)", marks a historical figure. */
    private val LIFE_SPAN = Regex("\\(\\s*(c\\.\\s*)?\\d{2,4}\\s*(BC|AD|BCE|CE)?\\s*[–-]\\s*\\d{2,4}")

    private val patterns = HashMap<String, Regex>()

    /** Whole-word match that also accepts simple plurals ("films", "churches"). */
    private fun wordPattern(word: String) =
        patterns.getOrPut(word) { Regex("\\b${Regex.escape(word)}(s|es)?\\b") }

    fun detect(description: String?, extract: String?, hasDictionary: Boolean): Category {
        // The short description ("Capital of France") is the strongest signal, so check it first.
        listOfNotNull(description, extract?.take(300)).forEach { text ->
            val lower = text.lowercase()
            RULES.forEach { (category, words) ->
                if (words.any { wordPattern(it).containsMatchIn(lower) }) return category
            }
        }
        if (description != null && LIFE_SPAN.containsMatchIn(description)) return HISTORY
        return if (hasDictionary) VOCABULARY else GENERAL
    }

    // endregion
}

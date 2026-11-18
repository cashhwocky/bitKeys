package com.bitdroid.keys

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min

/**
 * On-device predictions and spatial QWERTY autocorrect.
 *
 * Uses the 37k-word assets/words.txt dictionary + QWERTY key proximity + Damerau-Levenshtein
 * transposition + instant contraction & common misspelling dictionary.
 */
class Suggester(private val context: Context) {

    private class Entry(val form: String, val key: String, val rank: Int)

    @Volatile private var loaded = false
    private var words: List<Entry> = emptyList()
    private val exact = HashMap<String, Entry>()
    private val byKey = HashMap<String, Entry>()
    private val byLength = HashMap<Int, ArrayList<Entry>>()

    private var store: SharedPreferences? = null
    private val learned = HashMap<String, Int>()

    init {
        Thread {
            val list = ArrayList<Entry>(40000)
            runCatching {
                context.assets.open("words.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    var rank = 0
                    for (line in lines) {
                        val w = line.trim()
                        if (w.isEmpty()) continue
                        list.add(Entry(w, keyOf(w), rank++))
                    }
                }
            }
            val ex = HashMap<String, Entry>(list.size * 2)
            val bk = HashMap<String, Entry>(list.size * 2)
            val bl = HashMap<Int, ArrayList<Entry>>()
            for (e in list) {
                ex.putIfAbsent(e.form.lowercase(), e)
                bk.putIfAbsent(e.key, e)
                bl.getOrPut(e.key.length) { ArrayList() }.add(e)
            }
            synchronized(this) {
                words = list
                exact.putAll(ex)
                byKey.putAll(bk)
                byLength.putAll(bl)
                loaded = true
            }
        }.start()
    }

    private fun ensureStore(): SharedPreferences? {
        store?.let { return it }
        if (!Storage.isUnlocked(context)) return null
        val sp = context.getSharedPreferences("bitkeys_words", Context.MODE_PRIVATE)
        for ((k, v) in sp.all) if (v is Int) learned[k] = v
        store = sp
        return sp
    }

    private fun isLearned(lower: String): Boolean {
        ensureStore()
        return (learned[lower] ?: 0) >= 2
    }

    /**
     * The word that should replace [typed] when you hit space/punctuation, or null to leave it.
     */
    fun correction(typed: String): String? {
        if (!loaded || typed.isEmpty()) return null
        if (typed.any { !it.isLetter() && it != '\'' }) return null
        if (typed.drop(1).any { it.isUpperCase() } && !typed.all { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        if (isLearned(lower) || lower in NEVER_CORRECT) return null
        SPECIAL[lower]?.let { return matchCase(typed, it) }

        val key = keyOf(lower)
        val ex = exact[lower]
        val best = byKey[key]
        if (ex != null) {
            // Known word: swap for common contraction ("cant" -> "can't", "im" -> "I'm") or capitalization ("i" -> "I")
            if (best != null && best !== ex && ex.rank >= 4500 && best.rank < 1200) return matchCase(typed, best.form)
            return if (ex.form != lower && ex.form[0].isUpperCase() && typed != ex.form) ex.form else null
        }
        if (best != null) return matchCase(typed, best.form)

        if (key.length < 2) return null
        val maxD = when {
            key.length == 2 -> 0.75f
            key.length <= 4 -> 1.35f
            key.length <= 7 -> 1.95f
            else -> 2.45f
        }
        var bestEntry: Entry? = null
        var bestScore = Float.MAX_VALUE
        for (len in (key.length - 2)..(key.length + 2)) {
            val bucket = byLength[len] ?: continue
            for (e in bucket) {
                if (e.rank > 26000 && bestEntry != null) continue
                val d = distance(key, e.key, maxD)
                if (d > maxD) continue
                val firstLetterPenalty = if (e.key[0] == key[0]) 0f else 0.28f
                val score = d + firstLetterPenalty + 0.21f * log10(e.rank + 10f)
                if (score < bestScore) {
                    bestScore = score
                    bestEntry = e
                }
            }
        }
        return bestEntry?.let { matchCase(typed, it.form) }
    }

    /**
     * Three slots for the strip. The middle one is what space will produce:
     * the autocorrection if there is one, otherwise what you typed.
     */
    fun slots(typed: String, autocorrect: Boolean): List<String> {
        if (typed.isEmpty()) return emptyList()
        val corr = if (autocorrect) correction(typed) else null
        val completions = completions(typed, exclude = setOfNotNull(typed.lowercase(), corr?.lowercase()))
        return if (corr != null) {
            listOf(typed, corr, completions.getOrElse(0) { "" })
        } else {
            listOf(completions.getOrElse(0) { "" }, typed, completions.getOrElse(1) { "" })
        }
    }

    private fun completions(typed: String, exclude: Set<String>): List<String> {
        val lower = typed.lowercase()
        val key = keyOf(lower)
        val out = ArrayList<String>(2)
        ensureStore()
        learned.entries
            .filter { it.value >= 2 && it.key.length > lower.length && it.key.startsWith(lower) && it.key !in exclude }
            .sortedByDescending { it.value }
            .take(2)
            .forEach { out.add(matchCase(typed, it.key)) }
        if (loaded && out.size < 2) {
            for (e in words) {
                if (e.key.length > key.length && e.key.startsWith(key) && e.form.lowercase() !in exclude &&
                    out.none { it.equals(e.form, ignoreCase = true) }
                ) {
                    out.add(matchCase(typed, e.form))
                    if (out.size == 2) break
                }
            }
        }
        return out
    }

    fun learn(word: String, strong: Boolean = false) {
        val w = word.lowercase()
        if (w.length < 2 || w.any { !it.isLetter() && it != '\'' }) return
        val sp = ensureStore() ?: return
        val n = (learned[w] ?: 0) + if (strong) 2 else 1
        learned[w] = n
        sp.edit().putInt(w, n).apply()
    }

    fun forgetAll() {
        learned.clear()
        ensureStore()?.edit()?.clear()?.apply()
    }

    companion object {
        private fun keyOf(w: String) = w.lowercase().replace("'", "")

        private val NEVER_CORRECT = setOf(
            "lol", "lmao", "lmfao", "omg", "pls", "plz", "thx", "ty", "idk", "idc", "tbh", "imo", "imho",
            "brb", "btw", "rn", "ngl", "fr", "smh", "wyd", "hbu", "ikr", "np", "nvm", "ok", "okay", "ya",
            "yea", "yeah", "yep", "yup", "nah", "gonna", "wanna", "gotta", "kinda", "sorta", "haha",
            "hahaha", "lmk", "ttyl", "gg", "bruh", "bro", "sis", "fam", "tho", "cuz", "dm", "dms", "irl",
            "asap", "fyi", "aka", "tf", "wtf", "wth", "af", "ily", "hmu", "ofc", "sus", "lowkey",
            "highkey", "vibe", "vibes", "app", "apps", "ui", "ux", "wifi", "iphone", "pixel", "android",
            "github", "rom", "roms", "apk", "apks", "ota", "adb", "gboard", "bitdroid", "bitkeys",
            "gif", "gifs", "meme", "memes", "selfie", "emoji", "emojis", "hmm", "hmmm", "ugh", "aw",
            "aww", "ooh", "oof", "yay", "xd", "mhm", "uh", "um", "ur", "u", "k", "kk", "yo", "bet", "cap"
        )

        private val SPECIAL = mapOf(
            "alot" to "a lot", "tonite" to "tonight", "thanx" to "thanks", "nite" to "night",
            "becuz" to "because", "becuase" to "because", "beacuse" to "because", "bc" to "because",
            "sumthing" to "something", "somthing" to "something", "somethign" to "something", "soemthing" to "something",
            "everythign" to "everything", "anythign" to "anything", "nothign" to "nothing",
            "wat" to "what", "wut" to "what", "waht" to "what", "hwat" to "what",
            "prolly" to "probably", "probaly" to "probably",
            "tmrw" to "tomorrow", "tmr" to "tomorrow", "tommorow" to "tomorrow", "tomorow" to "tomorrow",
            "thru" to "through", "definatly" to "definitely", "definately" to "definitely", "definetly" to "definitely",
            "teh" to "the", "hte" to "the", "thw" to "the", "adn" to "and", "nad" to "and",
            "taht" to "that", "thta" to "that", "wiht" to "with", "wtih" to "with",
            "jsut" to "just", "jstu" to "just", "abotu" to "about", "abuot" to "about",
            "recieve" to "receive", "beleive" to "believe", "wierd" to "weird", "seperate" to "separate",
            "occured" to "occurred", "alreayd" to "already", "alredy" to "already",
            "acutally" to "actually", "actaully" to "actually", "realy" to "really", "relaly" to "really",
            "mispell" to "misspell", "autocorect" to "autocorrect", "keybaord" to "keyboard",
            "immedietaly" to "immediately", "immediatly" to "immediately", "imediatly" to "immediately",
            "thign" to "thing", "tihng" to "thing", "thigns" to "things", "peopel" to "people", "poeple" to "people",
            "freind" to "friend", "frien" to "friend", "kno" to "know", "knwo" to "know",
            "im" to "I'm", "ive" to "I've", "id" to "I'd", "ill" to "I'll",
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
            "cant" to "can't", "couldnt" to "couldn't", "wont" to "won't", "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't", "isnt" to "isn't", "arent" to "aren't",
            "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't", "havent" to "haven't",
            "youre" to "you're", "youve" to "you've", "youll" to "you'll",
            "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
            "thats" to "that's", "whats" to "what's", "heres" to "here's", "theres" to "there's",
            "wheres" to "where's", "lets" to "let's", "aint" to "ain't", "i" to "I"
        )

        fun matchCase(typed: String, word: String): String = when {
            word[0].isUpperCase() -> word
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val POS: Map<Char, Pair<Float, Float>> = buildMap {
            ROWS.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch -> put(ch, (c + r * 0.45f) to r.toFloat()) }
            }
        }

        private fun subCost(a: Char, b: Char): Float {
            if (a == b) return 0f
            val pa = POS[a] ?: return 1f
            val pb = POS[b] ?: return 1f
            val dist = hypot(pa.first - pb.first, pa.second - pb.second)
            return when {
                dist <= 1.25f -> 0.45f
                dist <= 1.85f -> 0.75f
                else -> 1.05f
            }
        }

        fun distance(a: String, b: String, max: Float): Float {
            if (abs(a.length - b.length) > max + 0.6f) return Float.MAX_VALUE
            val n = b.length
            var prev2 = FloatArray(n + 1)
            var prev = FloatArray(n + 1) { it * 0.85f }
            var cur = FloatArray(n + 1)
            for (i in 1..a.length) {
                cur[0] = i * 0.85f
                var rowMin = cur[0]
                for (j in 1..n) {
                    val del = if (i > 1 && a[i - 1] == a[i - 2]) 0.40f else 0.88f
                    val ins = if (j > 1 && b[j - 1] == b[j - 2]) 0.40f else 0.85f
                    var v = min(prev[j] + del, cur[j - 1] + ins)
                    v = min(v, prev[j - 1] + subCost(a[i - 1], b[j - 1]))
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        v = min(v, prev2[j - 2] + 0.45f)
                    }
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > max) return Float.MAX_VALUE
                val t = prev2; prev2 = prev; prev = cur; cur = t
            }
            return prev[n]
        }
    }
}

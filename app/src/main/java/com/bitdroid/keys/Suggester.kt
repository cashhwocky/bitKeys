package com.bitdroid.keys

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

/**
 * On-device predictions and autocorrect.
 *
 * Dictionary: assets/words.txt — ~37k correctly spelled English words, most common first
 * (built by tools/gen_words.py). Words you type are learned locally; a word counts as
 * "yours" (never corrected, suggested) once you've typed it twice, or immediately when
 * you undo an autocorrect or pick it from the strip.
 */
class Suggester(private val context: Context) {

    private class Entry(val form: String, val key: String, val rank: Int)

    @Volatile private var loaded = false
    private var words: List<Entry> = emptyList()
    private val exact = HashMap<String, Entry>()      // lowercase form -> entry
    private val byKey = HashMap<String, Entry>()      // lowercase form without apostrophes -> best entry
    private val byLength = HashMap<Int, ArrayList<Entry>>()

    // Learned words stay in credential-encrypted storage (only after first unlock).
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

    // ------------------------------------------------------------------ autocorrect

    /**
     * The word that should replace [typed] when you hit space/punctuation, or null to leave it.
     * Fixes typos, missing apostrophes (dont → don't, im → I'm) and capitals (i → I).
     */
    fun correction(typed: String): String? {
        if (!loaded || typed.isEmpty()) return null
        if (typed.any { !it.isLetter() && it != '\'' }) return null
        // Mixed case like "iPhone" or "McDonald": assume it's intentional.
        if (typed.drop(1).any { it.isUpperCase() } && !typed.all { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        if (isLearned(lower) || lower in NEVER_CORRECT) return null
        SPECIAL[lower]?.let { return matchCase(typed, it) }

        val key = keyOf(lower)
        val ex = exact[lower]
        val best = byKey[key]
        if (ex != null) {
            // A real word. Only swap it for a much more common apostrophe form ("cant" → "can't"),
            // or fix its capitalization ("i" → "I", "english" → "English").
            if (best != null && best !== ex && ex.rank >= 5000 && best.rank < 1000) return matchCase(typed, best.form)
            return if (ex.form != lower && ex.form[0].isUpperCase() && typed != ex.form) ex.form else null
        }
        if (best != null) return matchCase(typed, best.form) // "dont" → "don't"

        if (key.length < 3) return null
        val maxD = when {
            key.length <= 4 -> 1.0f
            key.length <= 7 -> 1.6f
            else -> 2.0f
        }
        var bestEntry: Entry? = null
        var bestScore = Float.MAX_VALUE
        for (len in (key.length - 2)..(key.length + 2)) {
            val bucket = byLength[len] ?: continue
            for (e in bucket) {
                if (e.rank > 25000 && bestEntry != null) continue
                if (e.form.contains('\'')) continue // contractions only match exactly ("dont"), never fuzzily
                val d = distance(key, e.key, maxD)
                if (d > maxD) continue
                val score = d + 0.22f * log10(e.rank + 10f)
                if (score < bestScore) {
                    bestScore = score
                    bestEntry = e
                }
            }
        }
        return bestEntry?.let { matchCase(typed, it.form) }
    }

    // ------------------------------------------------------------------ suggestion strip

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

    // ------------------------------------------------------------------ learning

    /** [strong] = the user clearly meant this exact word (undid an autocorrect / picked it). */
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

    // ------------------------------------------------------------------ helpers

    companion object {
        private fun keyOf(w: String) = w.lowercase().replace("'", "")

        /** Texting words and tech terms that must never be "fixed". */
        private val NEVER_CORRECT = setOf(
            "lol", "lmao", "lmfao", "omg", "pls", "plz", "thx", "ty", "idk", "idc", "tbh", "imo", "imho",
            "brb", "btw", "rn", "ngl", "fr", "smh", "wyd", "hbu", "ikr", "np", "nvm", "ok", "okay", "ya",
            "yea", "yeah", "yep", "yup", "nah", "gonna", "wanna", "gotta", "kinda", "sorta", "haha",
            "hahaha", "lmk", "ttyl", "gg", "bruh", "bro", "sis", "fam", "tho", "cuz", "dm", "dms", "irl",
            "asap", "fyi", "aka", "tf", "wtf", "wth", "af", "ily", "hmu", "ofc", "sus", "lowkey",
            "highkey", "vibe", "vibes", "app", "apps", "ui", "ux", "wifi", "iphone", "pixel", "android",
            "github", "rom", "roms", "apk", "apks", "ota", "adb", "gboard", "bitdroid", "bitkeys",
            "gif", "gifs", "meme", "memes", "selfie", "emoji", "emojis", "hmm", "hmmm", "ugh", "aw",
            "aww", "ooh", "oof", "yay", "xd", "mhm", "uh", "um", "ur", "u", "k", "kk",
        )

        /** Common misspellings too far from the right word for the fuzzy matcher. */
        private val SPECIAL = mapOf(
            "alot" to "a lot", "tonite" to "tonight", "thanx" to "thanks", "nite" to "night",
            "becuz" to "because", "sumthing" to "something", "somthing" to "something",
            "wat" to "what", "wut" to "what", "prolly" to "probably",
            "tmrw" to "tomorrow", "tmr" to "tomorrow", "thru" to "through", "definatly" to "definitely",
        )

        fun matchCase(typed: String, word: String): String = when {
            word[0].isUpperCase() -> word // proper nouns / "I'm" keep their capitals
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val POS: Map<Char, Pair<Float, Float>> = buildMap {
            ROWS.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch -> put(ch, (c + r * 0.5f) to r.toFloat()) }
            }
        }

        /** Substituting a neighbouring key is a cheaper (more likely) mistake. */
        private fun subCost(a: Char, b: Char): Float {
            if (a == b) return 0f
            val pa = POS[a] ?: return 1f
            val pb = POS[b] ?: return 1f
            val near = abs(pa.first - pb.first) <= 1.01f && abs(pa.second - pb.second) <= 1.01f
            return if (near) 0.6f else 1f
        }

        /** Weighted Damerau–Levenshtein (optimal string alignment) with early exit past [max]. */
        fun distance(a: String, b: String, max: Float): Float {
            if (abs(a.length - b.length) > max) return Float.MAX_VALUE
            val n = b.length
            var prev2 = FloatArray(n + 1)
            var prev = FloatArray(n + 1) { it.toFloat() }
            var cur = FloatArray(n + 1)
            for (i in 1..a.length) {
                cur[0] = i.toFloat()
                var rowMin = cur[0]
                for (j in 1..n) {
                    // A missed or extra doubled letter ("helo", "untill") is a very common slip.
                    val del = if (i > 1 && a[i - 1] == a[i - 2]) 0.45f else 1f
                    val ins = if (j > 1 && b[j - 1] == b[j - 2]) 0.45f else 1f
                    var v = min(prev[j] + del, cur[j - 1] + ins)
                    v = min(v, prev[j - 1] + subCost(a[i - 1], b[j - 1]))
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        v = min(v, prev2[j - 2] + 0.7f)
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

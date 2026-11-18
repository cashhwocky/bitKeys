package com.bitdroid.keys

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class SuggestionResult(
    val candidates: List<String>,
    val primaryCorrection: String?,
    val shouldAutoCommit: Boolean
)

class Suggester(private val context: Context) {

    private val wordFreq = HashMap<String, Int>(25000)
    private val prefixBuckets = HashMap<String, MutableList<String>>(2500)
    private val lengthBuckets = HashMap<Int, MutableList<String>>(24)
    private val userBoost = HashMap<String, Int>()

    // Direct instant-fix map for common typos, contractions, and conversational words
    private val instantFixes = mapOf(
        "teh" to "the", "hte" to "the", "thw" to "the", "tje" to "the",
        "adn" to "and", "nad" to "and", "anf" to "and",
        "taht" to "that", "thta" to "that", "thst" to "that",
        "waht" to "what", "hwat" to "what", "wat" to "what",
        "wiht" to "with", "wtih" to "with", "woth" to "with",
        "jsut" to "just", "jstu" to "just",
        "abotu" to "about", "abuot" to "about",
        "becuase" to "because", "beacuse" to "because", "bc" to "because",
        "recieve" to "receive", "beleive" to "believe", "wierd" to "weird",
        "definately" to "definitely", "definetly" to "definitely",
        "seperate" to "separate", "occured" to "occurred",
        "tommorow" to "tomorrow", "tomorow" to "tomorrow",
        "alreayd" to "already", "alredy" to "already",
        "probaly" to "probably", "prolly" to "probably",
        "acutally" to "actually", "actaully" to "actually",
        "somethign" to "something", "soemthing" to "something",
        "everythign" to "everything", "anythign" to "anything",
        "nothign" to "nothing", "kno" to "know", "knwo" to "know",
        "frien" to "friend", "freind" to "friend",
        "peopel" to "people", "poeple" to "people",
        "thign" to "thing", "tihng" to "thing", "thigns" to "things",
        "realy" to "really", "relaly" to "really",
        "mispell" to "misspell", "autocorect" to "autocorrect",
        "keybaord" to "keyboard", "keyborad" to "keyboard",
        "im" to "I'm", "ive" to "I've", "id" to "I'd", "ill" to "I'll",
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "cant" to "can't", "couldnt" to "couldn't",
        "wont" to "won't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "isnt" to "isn't", "arent" to "aren't",
        "wasnt" to "wasn't", "werent" to "weren't",
        "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
        "youre" to "you're", "youve" to "you've", "youd" to "you'd", "youll" to "you'll",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "weve" to "we've", "were" to "we're",
        "thats" to "that's", "whats" to "what's", "heres" to "here's",
        "theres" to "there's", "wheres" to "where's", "whos" to "who's",
        "lets" to "let's", "itll" to "it'll", "ain't" to "ain't", "aint" to "ain't",
        "i" to "I"
    )

    // Safe modern slang & conversational words that should NEVER be forcefully corrupted
    private val protectedVocabulary = setOf(
        "bro", "bruh", "lowkey", "highkey", "fr", "ngl", "tbh", "idk", "ikr",
        "imo", "rn", "irl", "afk", "gg", "goat", "fire", "bet", "vibe", "vibes",
        "cap", "nocap", "finna", "gonna", "wanna", "gotta", "kinda", "sorta",
        "yeah", "yep", "nah", "nope", "yo", "hey", "ok", "okay", "lol", "lmao",
        "omg", "wtf", "dm", "dms", "app", "apps", "rom", "roms", "ota", "aosp",
        "adb", "fastboot", "pixel", "gboard", "bitkeys", "github", "ubuntu", "linux",
        "fl", "beats", "rap", "track", "gym", "workout"
    )

    // Contextual next-word bigrams
    private val bigrams = mapOf(
        "i" to listOf("am", "have", "think", "want"),
        "i'm" to listOf("going", "not", "just", "on"),
        "im" to listOf("going", "not", "just", "on"),
        "are" to listOf("you", "we", "they"),
        "what" to listOf("is", "are", "do", "time"),
        "how" to listOf("are", "to", "is", "much"),
        "can" to listOf("you", "I", "we"),
        "do" to listOf("you", "not", "it"),
        "don't" to listOf("know", "think", "want", "worry"),
        "going" to listOf("to", "on", "home"),
        "want" to listOf("to", "it", "that"),
        "need" to listOf("to", "a", "some"),
        "thank" to listOf("you", "god"),
        "thanks" to listOf("for", "bro", "man"),
        "see" to listOf("you", "if", "what"),
        "let" to listOf("me", "us", "it"),
        "on" to listOf("the", "my", "it"),
        "in" to listOf("the", "my", "a"),
        "for" to listOf("the", "you", "me"),
        "with" to listOf("the", "you", "my"),
        "this" to listOf("is", "one", "week")
    )

    // Physical QWERTY coordinates (x, y) in key units
    private val keyCoords: Map<Char, Pair<Float, Float>> = buildMap {
        val row0 = "qwertyuiop"
        val row1 = "asdfghjkl"
        val row2 = "zxcvbnm"
        row0.forEachIndexed { i, c -> put(c, i.toFloat() to 0f) }
        row1.forEachIndexed { i, c -> put(c, (i + 0.35f) to 1f) }
        row2.forEachIndexed { i, c -> put(c, (i + 0.85f) to 2f) }
    }

    init {
        loadDictionary()
        reloadUserWords()
    }

    fun reloadUserWords() {
        userBoost.clear()
        userBoost.putAll(Storage.getUserWords(context))
        userBoost.forEach { (w, boost) ->
            val base = wordFreq[w] ?: 140
            wordFreq[w] = base + boost * 25
            indexWord(w)
        }
    }

    private fun indexWord(w: String) {
        val p1 = w.take(1)
        val p2 = w.take(min(2, w.length))
        val b1 = prefixBuckets.getOrPut(p1) { mutableListOf() }
        if (b1.size < 450 && !b1.contains(w)) b1.add(w)
        val b2 = prefixBuckets.getOrPut(p2) { mutableListOf() }
        if (b2.size < 350 && !b2.contains(w)) b2.add(w)
        val lb = lengthBuckets.getOrPut(w.length) { mutableListOf() }
        if (lb.size < 2500) lb.add(w)
    }

    private fun loadDictionary() {
        // Seed core high-frequency English + conversational words first
        val coreTop = listOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
            "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
            "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
            "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
            "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
            "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            "really", "right", "still", "going", "need", "where", "something", "much", "thing", "things",
            "phone", "keyboard", "better", "always", "never", "actually", "probably", "already", "message", "typing",
            "misspell", "autocorrect", "official", "google", "gboard", "design", "layout", "setup", "screen", "script",
            "around", "through", "before", "should", "little", "world", "school", "Every", "friend", "friends"
        )
        coreTop.forEachIndexed { idx, raw ->
            val w = raw.lowercase()
            wordFreq[w] = 1000 - idx * 3
            indexWord(w)
        }
        protectedVocabulary.forEach { w ->
            wordFreq.putIfAbsent(w, 650)
            indexWord(w)
        }

        try {
            context.assets.open("words.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var rank = 0
                    var line = reader.readLine()
                    while (line != null && rank < 28000) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val parts = trimmed.split('\t', ' ')
                            val word = parts[0].trim().lowercase()
                            if (word.length in 1..18 && word.all { it.isLetter() || it == '\'' }) {
                                val explicitScore = if (parts.size > 1) parts[1].toIntOrNull() else null
                                val freqScore = explicitScore ?: max(30, 920 - (rank / 28))
                                val existing = wordFreq[word] ?: 0
                                if (freqScore > existing) {
                                    wordFreq[word] = freqScore
                                }
                                indexWord(word)
                                rank++
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback already seeded above
        }
    }

    fun nextWordPredictions(previousWord: String?): List<String> {
        val clean = previousWord?.trim()?.lowercase() ?: ""
        val list = bigrams[clean]
        if (!list.isNullOrEmpty()) return list.take(3)
        return listOf("I", "the", "you")
    }

    /**
     * Spatial QWERTY substitution cost between two characters:
     * - Identical char: 0.0
     * - Adjacent physical key (distance <= 1.35): 0.42
     * - Diagonal / nearby key (distance <= 2.1): 0.80
     * - Distant key: 1.35
     */
    private fun keySubstitutionCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        val ca = keyCoords[a] ?: return 1.25f
        val cb = keyCoords[b] ?: return 1.25f
        val dist = hypot(ca.first - cb.first, ca.second - cb.second)
        return when {
            dist <= 1.25f -> 0.42f
            dist <= 1.85f -> 0.78f
            else -> 1.30f
        }
    }

    /**
     * Weighted Damerau-Levenshtein with QWERTY physical proximity costs
     * and transposition support.
     */
    private fun spatialEditDistance(source: String, target: String, maxAllowed: Float = 3.2f): Float {
        val n = source.length
        val m = target.length
        if (abs(n - m) > 3) return 99f
        if (source == target) return 0f

        var prevPrev = FloatArray(m + 1)
        var prev = FloatArray(m + 1) { it * 0.9f }
        var curr = FloatArray(m + 1)

        for (i in 1..n) {
            curr[0] = i * 0.95f
            var rowMin = curr[0]
            val sc = source[i - 1]

            for (j in 1..m) {
                val tc = target[j - 1]
                val subCost = keySubstitutionCost(sc, tc)

                var best = min(
                    min(prev[j] + 0.92f, curr[j - 1] + 0.85f), // deletion vs insertion
                    prev[j - 1] + subCost
                )

                // Adjacent transposition (e.g., "teh" -> "the", "adn" -> "and")
                if (i > 1 && j > 1 && sc == target[j - 2] && source[i - 2] == tc) {
                    best = min(best, prevPrev[j - 2] + 0.35f)
                }

                curr[j] = best
                if (best < rowMin) rowMin = best
            }

            if (rowMin > maxAllowed) return 99f

            val tmp = prevPrev
            prevPrev = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    private fun applyCasing(template: String, candidate: String): String {
        if (template.isEmpty() || candidate.isEmpty()) return candidate
        if (candidate.startsWith("I'") || candidate == "I") return candidate
        return when {
            template.length > 1 && template.all { it.isUpperCase() } -> candidate.uppercase()
            template[0].isUpperCase() -> candidate.replaceFirstChar { it.uppercase() }
            else -> candidate
        }
    }

    fun evaluate(rawInput: String, previousWord: String? = null): SuggestionResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return SuggestionResult(nextWordPredictions(previousWord), null, false)
        }

        val lower = trimmed.lowercase()

        // 1. Check direct instant fixes first (contractions + notorious typos)
        val directFix = instantFixes[lower]
        if (directFix != null) {
            val casedFix = applyCasing(trimmed, directFix)
            val alts = mutableListOf(casedFix)
            if (casedFix != trimmed) alts.add(trimmed)
            // Add one close completion
            prefixBuckets[lower.take(min(2, lower.length))]?.firstOrNull {
                it != lower && it != directFix.lowercase()
            }?.let { alts.add(applyCasing(trimmed, it)) }

            return SuggestionResult(
                candidates = alts.distinct().take(3),
                primaryCorrection = casedFix,
                shouldAutoCommit = Storage.autoCorrect && !casedFix.equals(trimmed, ignoreCase = false)
            )
        }

        // 2. If it's a protected slang/tech word or known high-frequency exact match
        val exactFreq = wordFreq[lower] ?: 0
        val isKnownExact = exactFreq >= 160 || protectedVocabulary.contains(lower) || userBoost.containsKey(lower)

        // Gather candidate pool:
        // - Same 2-char prefix
        // - Same 1-char prefix
        // - Nearby lengths (in case first letter was fat-fingered on QWERTY!)
        val candidatePool = HashSet<String>(800)
        prefixBuckets[lower.take(min(2, lower.length))]?.let { candidatePool.addAll(it) }
        prefixBuckets[lower.take(1)]?.let { candidatePool.addAll(it.take(250)) }

        // Also check QWERTY neighbors of the first character for first-letter typos
        val firstChar = lower[0]
        val firstCoord = keyCoords[firstChar]
        if (firstCoord != null) {
            keyCoords.forEach { (neighborChar, coord) ->
                if (neighborChar != firstChar && hypot(firstCoord.first - coord.first, firstCoord.second - coord.second) <= 1.35f) {
                    val altPrefix = "$neighborChar" + if (lower.length > 1) lower[1] else ""
                    prefixBuckets[altPrefix]?.let { candidatePool.addAll(it.take(120)) }
                    prefixBuckets["$neighborChar"]?.let { candidatePool.addAll(it.take(80)) }
                }
            }
        }

        for (len in max(2, lower.length - 1)..min(16, lower.length + 2)) {
            lengthBuckets[len]?.let { bucket ->
                candidatePool.addAll(bucket.take(320))
            }
        }

        val bigramBoostSet = bigrams[previousWord?.lowercase() ?: ""]?.map { it.lowercase() }?.toSet() ?: emptySet()

        data class ScoredWord(val word: String, val score: Float, val distance: Float)
        val scored = ArrayList<ScoredWord>(48)

        for (cand in candidatePool) {
            if (abs(cand.length - lower.length) > 3) continue

            val freq = (wordFreq[cand] ?: 50).toFloat()
            val contextBonus = if (bigramBoostSet.contains(cand)) 210f else 0f
            val userBonus = (userBoost[cand] ?: 0) * 45f

            if (cand == lower) {
                scored.add(ScoredWord(cand, 1800f + freq + contextBonus + userBonus, 0f))
                continue
            }

            // Prefix completion candidate
            if (cand.startsWith(lower) && cand.length <= lower.length + 4) {
                val extraChars = cand.length - lower.length
                val completionScore = 1180f + (freq * 0.75f) - (extraChars * 95f) + contextBonus + userBonus
                scored.add(ScoredWord(cand, completionScore, extraChars * 0.45f))
                continue
            }

            val dist = spatialEditDistance(lower, cand, if (lower.length <= 4) 1.85f else 2.75f)
            if (dist > 2.75f) continue

            val firstCharBonus = if (cand[0] == lower[0]) 140f else -45f
            val lastCharBonus = if (cand.last() == lower.last()) 75f else 0f
            val lengthPenalty = abs(cand.length - lower.length) * 65f

            val totalScore = 1550f - (dist * 470f) + (freq * 0.68f) + firstCharBonus + lastCharBonus - lengthPenalty + contextBonus + userBonus
            scored.add(ScoredWord(cand, totalScore, dist))
        }

        scored.sortByDescending { it.score }

        val topMatches = scored
            .map { applyCasing(trimmed, it.word) }
            .distinct()
            .take(4)

        val best = scored.firstOrNull { it.word != lower }
        val exactScored = scored.firstOrNull { it.word == lower }

        // Decide whether Spacebar should auto-commit the top correction
        var primaryCorrection: String? = null
        var shouldAutoCommit = false

        if (Storage.autoCorrect && !isKnownExact && lower.length >= 2 && best != null) {
            val thresholdScore = if (Storage.aggressiveAutocorrect) 980f else 1180f
            val maxDist = if (lower.length <= 3) 1.15f else 2.25f
            if (best.score >= thresholdScore && best.distance <= maxDist) {
                // Also ensure replacement isn't drastically shorter unless edit distance is tiny
                if (best.word.length >= lower.length - 1) {
                    primaryCorrection = applyCasing(trimmed, best.word)
                    shouldAutoCommit = true
                }
            }
        } else if (Storage.autoCorrect && isKnownExact && best != null && exactScored != null) {
            // Only override a known word if it's a very low-frequency word AND a massive bigram/spatial winner exists
            if (exactFreq < 90 && best.distance <= 0.45f && (best.score - exactScored.score) > 380f) {
                primaryCorrection = applyCasing(trimmed, best.word)
                shouldAutoCommit = true
            }
        }

        // Check if apostrophe restoration matches (e.g. user typed "cant" or "dont")
        instantFixes[lower]?.let { fixed ->
            primaryCorrection = applyCasing(trimmed, fixed)
            shouldAutoCommit = Storage.autoCorrect
        }

        // Format final 3-slot list for Gboard candidate bar:
        // We return ordered candidates: [best primary, exact/second, third]
        val finalCandidates = mutableListOf<String>()
        if (primaryCorrection != null) {
            finalCandidates.add(primaryCorrection)
        }
        if (!finalCandidates.contains(trimmed)) {
            finalCandidates.add(trimmed)
        }
        for (m in topMatches) {
            if (!finalCandidates.contains(m)) {
                finalCandidates.add(m)
            }
        }
        while (finalCandidates.size < 3) {
            val fallback = listOf("the", "and", "to")[finalCandidates.size]
            if (!finalCandidates.contains(fallback)) finalCandidates.add(fallback) else break
        }

        return SuggestionResult(
            candidates = finalCandidates.take(3),
            primaryCorrection = primaryCorrection,
            shouldAutoCommit = shouldAutoCommit
        )
    }
}

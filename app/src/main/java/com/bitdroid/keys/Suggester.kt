package com.bitdroid.keys

import android.content.Context

/**
 * Tiny on-device word predictor: a built-in list of common English words plus
 * words the user has typed (stored locally, never leaves the device).
 */
class Suggester(context: Context) {

    private val store = context.getSharedPreferences("bitkeys_words", Context.MODE_PRIVATE)
    private val learned = HashMap<String, Int>()

    init {
        for ((k, v) in store.all) {
            if (v is Int) learned[k] = v
        }
    }

    /**
     * Returns exactly three slots for the suggestion strip: [verbatim, best, second].
     * Empty strings mean "nothing in this slot". The middle slot is the strongest.
     */
    fun slots(typed: String): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val candidates = (learned.keys.asSequence() + BASE.asSequence())
            .filter { it.length > lower.length && it.startsWith(lower) }
            .distinct()
            .sortedWith(
                compareByDescending<String> { learned[it] ?: 0 }
                    .thenBy { RANK[it] ?: Int.MAX_VALUE }
            )
            .take(2)
            .map { matchCase(typed, it) }
            .toList()

        return when (candidates.size) {
            0 -> listOf("", typed, "")
            1 -> listOf(typed, candidates[0], "")
            else -> listOf(typed, candidates[0], candidates[1])
        }
    }

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.any { !it.isLetter() && it != '\'' }) return
        val n = (learned[w] ?: 0) + 1
        learned[w] = n
        store.edit().putInt(w, n).apply()
    }

    fun forgetAll() {
        learned.clear()
        store.edit().clear().apply()
    }

    companion object {
        fun matchCase(typed: String, word: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        private val BASE: List<String> = """
            the be to of and a in that have i it for not on with he as you do at this but his by from they
            we say her she or an will my one all would there their what so up out if about who get which go me
            when make can like time no just him know take people into year your good some could them see other
            than then now look only come its over think also back after use two how our work first well way even
            new want because any these give day most us is are was were been has had did said going really thanks
            thank yes yeah okay sure maybe tonight tomorrow today later soon morning night evening weekend
            minutes hours home here where why what's that's don't can't won't i'm i'll i've you're we're they're
            it's let's love great nice cool awesome perfect happy sorry please hello hey hi bye see soon
            still already almost always never sometimes again something nothing everything anything someone
            everyone anyone call text send message phone meet meeting dinner lunch breakfast coffee food eat
            drive car leave leaving left right wait waiting coming came went done finished start started stop
            help need needs feel feeling think thought tell told ask asked try trying play playing watch
            watching game music song movie show house place school class work working job friend friends
            family mom dad brother sister baby kids little big long short fast slow late early ready busy free
            open close closed money pay buy bought store shop order pick picking up down off around through
            between before after during while until since about above below under over inside outside near far
            better best worse worst more less much many few every each both either neither own same different
            important problem question answer idea plan plans trip travel flight hotel room bed sleep tired
            sick doctor hospital weather rain sunny cold hot warm outside birthday party congrats congratulations
            awesome amazing beautiful funny crazy weird honestly literally actually probably definitely
            totally exactly basically anyway though although however because also too very really pretty
            update phone battery charger wifi internet email photo picture video link app download install
            android pixel rom build release github telegram keyboard voice typing settings theme
        """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }

        private val RANK: Map<String, Int> = BASE.withIndex().associate { (i, w) -> w to i }
    }
}

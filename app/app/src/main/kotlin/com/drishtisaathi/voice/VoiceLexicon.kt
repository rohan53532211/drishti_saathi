package com.drishtisaathi.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keyword lexicon ("voiceKeywords" in `assets/anamnesis_questions.json`). Strict schema: unknown keys are rejected, and [validate]
 * enforces the rules the parser relies on. `hi` lists are Devanagari, `en` lists are Latin script
 * (English and romanised Hindi).
 */
@Serializable
data class VoiceLexicon(
    val version: Int,
    val notes: String = "",
    val yes: Keywords,
    val no: Keywords,
    val weakYes: Keywords,
    val dontKnow: Keywords,
    /** After a yes word these are question tags, not negation: "हाँ ना" = yes. */
    val tagWords: Keywords,
    /** Words that negate the keyword just before them (Hindi word order: "दुकान नहीं"). */
    val negators: Keywords,
    /** Words that negate the keyword just after them ("not office", "बिना"). */
    val preNegators: Keywords,
    val numbers: NumberWords,
    /** questionId → choice lexicon. */
    val choices: Map<String, ChoiceLexicon>,
) {
    @Serializable
    data class Keywords(val hi: List<String>, val en: List<String>) {
        val all: List<String> get() = hi + en
    }

    @Serializable
    data class NumberWords(
        val words: Map<String, Int>,
        val hundred: List<String>,
        val thousand: List<String>,
    )

    @Serializable
    enum class NumberMode {
        /** Spoken numbers are ignored for this question. */
        @SerialName("none") NONE,
        /** A spoken number picks the option whose [Option.range] contains it (e.g. screen hours). */
        @SerialName("range") RANGE,
        /** A spoken rupee amount is compared with the budget caps from rules.json (options LOW, MID, HIGH). */
        @SerialName("budgetCaps") BUDGET_CAPS,
    }

    @Serializable
    data class ChoiceLexicon(
        val numberMode: NumberMode = NumberMode.NONE,
        /** Enum constant name → keywords. */
        val options: Map<String, Option>,
    )

    @Serializable
    data class Option(
        val hi: List<String>,
        val en: List<String>,
        /** Inclusive [min, max] for [NumberMode.RANGE]. */
        val range: List<Int>? = null,
        /** When several options match, only the highest priority ones compete ("both", "any"). */
        val priority: Int = 0,
    ) {
        val all: List<String> get() = hi + en
    }

    /** Returns a list of problems; empty means the lexicon is usable. */
    fun validate(): List<String> {
        val errs = mutableListOf<String>()
        fun checkKw(name: String, hi: List<String>, en: List<String>, requireBoth: Boolean = true) {
            if (requireBoth && hi.none { it.isNotBlank() }) errs += "$name: no Hindi keyword"
            if (requireBoth && en.none { it.isNotBlank() }) errs += "$name: no English keyword"
            hi.filterNot { TextNormalizer.isDevanagari(it) }.forEach { errs += "$name: hi entry '$it' is not Devanagari" }
            en.filterNot { TextNormalizer.isLatin(it) }.forEach { errs += "$name: en entry '$it' is not Latin script" }
            (hi + en).filter { TextNormalizer.normalize(it).isEmpty() }.forEach { errs += "$name: empty keyword '$it'" }
        }
        checkKw("yes", yes.hi, yes.en)
        checkKw("no", no.hi, no.en)
        checkKw("weakYes", weakYes.hi, weakYes.en)
        checkKw("dontKnow", dontKnow.hi, dontKnow.en)
        checkKw("tagWords", tagWords.hi, tagWords.en)
        checkKw("negators", negators.hi, negators.en)
        checkKw("preNegators", preNegators.hi, preNegators.en, requireBoth = false)

        val yesSet = yes.all.map(TextNormalizer::normalize).toSet()
        val noSet = no.all.map(TextNormalizer::normalize).toSet()
        (yesSet intersect noSet).forEach { errs += "'$it' is both yes and no" }
        (weakYes.all.map(TextNormalizer::normalize).toSet() intersect (yesSet + noSet)).forEach { errs += "weakYes '$it' also in yes/no" }

        numbers.words.forEach { (w, v) ->
            if (v !in 0..99) errs += "number word '$w' = $v out of 0..99"
            if (TextNormalizer.tokens(w).size != 1) errs += "number word '$w' must be one word"
        }
        if (numbers.hundred.isEmpty()) errs += "numbers.hundred empty"
        if (numbers.thousand.isEmpty()) errs += "numbers.thousand empty"

        choices.forEach { (qid, c) ->
            if (c.options.isEmpty()) errs += "$qid: no options"
            val owner = mutableMapOf<String, String>()
            c.options.forEach { (opt, o) ->
                checkKw("$qid.$opt", o.hi, o.en)
                o.all.map(TextNormalizer::normalize).toSet().forEach { k ->
                    val prev = owner.put(k, opt)
                    if (prev != null && prev != opt) errs += "$qid: '$k' maps to both $prev and $opt"
                }
                when (c.numberMode) {
                    NumberMode.RANGE -> {
                        val r = o.range
                        if (r == null || r.size != 2 || r[0] > r[1]) errs += "$qid.$opt: range must be [min, max]"
                    }
                    else -> if (o.range != null) errs += "$qid.$opt: range only allowed with numberMode=range"
                }
            }
            if (c.numberMode == NumberMode.BUDGET_CAPS && c.options.keys != setOf("LOW", "MID", "HIGH")) {
                errs += "$qid: budgetCaps needs exactly LOW, MID, HIGH"
            }
            if (c.numberMode == NumberMode.RANGE) {
                val ranges = c.options.values.mapNotNull { it.range?.takeIf { r -> r.size == 2 } }.sortedBy { it[0] }
                ranges.zipWithNext().forEach { (a, b) -> if (b[0] <= a[1]) errs += "$qid: ranges overlap $a $b" }
            }
        }
        return errs
    }

    companion object {
        /** The lexicon lives in the question file, under "voiceKeywords". */
        const val ASSET = com.drishtisaathi.interview.QuestionBank.ASSET
        private val json = Json { ignoreUnknownKeys = false }

        /** Parses and validates; throws [IllegalArgumentException] listing every problem. */
        fun parse(text: String): VoiceLexicon {
            val lex = json.decodeFromString(serializer(), text)
            val errs = lex.validate()
            require(errs.isEmpty()) { "voice_lexicon.json invalid:\n" + errs.joinToString("\n") }
            return lex
        }

        /** The "voiceKeywords" object of the question file, parsed and validated like [parse]. */
        fun fromQuestionsFile(text: String): VoiceLexicon {
            val root = Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            val kw = requireNotNull(root?.get("voiceKeywords")) { "question file has no voiceKeywords" }
            return parse(kw.toString())
        }
    }
}

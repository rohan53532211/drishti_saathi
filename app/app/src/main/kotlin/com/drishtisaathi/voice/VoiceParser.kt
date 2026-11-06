package com.drishtisaathi.voice

import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question

/** A spoken answer in the shape of the question it answers. */
sealed interface VoiceAnswer {
    data class YesNo(val value: Boolean) : VoiceAnswer
    /** null = "don't know" (only for questions that allow it). */
    data class Number(val value: Int?) : VoiceAnswer
    /** One of the question's options (an enum constant). */
    data class Choice(val option: Any) : VoiceAnswer
}

sealed interface ParseResult {
    /** [confidence] in 0..1: keyword coverage of the utterance × recogniser confidence when given. */
    data class Parsed(val answer: VoiceAnswer, val confidence: Double) : ParseResult
    /** Several different answers were heard ("35 या 36", "हाँ… नहीं"). */
    data class Ambiguous(val candidates: List<VoiceAnswer>) : ParseResult
    data object NoMatch : ParseResult
}

/**
 * Keyword-lexicon parser: maps recognised Hindi / Hinglish / English text to a question's answer type.
 * Pure Kotlin; all vocabulary comes from [VoiceLexicon].
 */
class VoiceParser(private val lex: VoiceLexicon) {

    private enum class YN { DONT_KNOW, NO, YES, WEAK_YES }

    private val ynMatcher = PhraseMatcher(
        // Earlier entries win when the same phrase is listed twice.
        lex.dontKnow.all.map { it to YN.DONT_KNOW } + lex.no.all.map { it to YN.NO } +
            lex.yes.all.map { it to YN.YES } + lex.weakYes.all.map { it to YN.WEAK_YES },
    )
    private val dontKnowMatcher = PhraseMatcher(lex.dontKnow.all.map { it to Unit })
    private val tags = lex.tagWords.all.map(TextNormalizer::normalize).toSet()
    private val postNeg = lex.negators.all.map(TextNormalizer::normalize).toSet()
    private val preNeg = lex.preNegators.all.map(TextNormalizer::normalize).toSet()
    private val numberWords = lex.numbers.words.mapKeys { TextNormalizer.normalize(it.key) }
    private val hundreds = lex.numbers.hundred.map(TextNormalizer::normalize).toSet()
    private val thousands = lex.numbers.thousand.map(TextNormalizer::normalize).toSet()
    private val choiceMatchers = lex.choices.mapValues { (_, c) ->
        PhraseMatcher(c.options.flatMap { (opt, o) -> o.all.map { it to opt } })
    }

    /** Parses the n-best list from the recogniser: the first hypothesis that parses wins. */
    fun parse(q: Question, hypotheses: List<String>, ctx: ChoiceContext, confidences: List<Float>? = null): ParseResult {
        val ambiguous = mutableListOf<VoiceAnswer>()
        hypotheses.forEachIndexed { i, h ->
            when (val r = parse(q, h, ctx)) {
                is ParseResult.Parsed -> {
                    val rc = confidences?.getOrNull(i)?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
                    return r.copy(confidence = (r.confidence * rc).coerceIn(0.0, 1.0))
                }
                is ParseResult.Ambiguous -> r.candidates.forEach { if (it !in ambiguous) ambiguous += it }
                ParseResult.NoMatch -> {}
            }
        }
        return if (ambiguous.isNotEmpty()) ParseResult.Ambiguous(ambiguous) else ParseResult.NoMatch
    }

    fun parse(q: Question, text: String, ctx: ChoiceContext): ParseResult = when (q) {
        is Question.YesNo, is Question.OldNumbers -> parseYesNo(text)
        is Question.Number -> parseNumber(text, q.range, q.allowUnknown)
        is Question.Choice<*> -> parseChoice(q.id, q.options, text, ctx)
    }

    // ---- yes / no ------------------------------------------------------------------------------

    fun parseYesNo(text: String): ParseResult {
        val tokens = TextNormalizer.tokens(text)
        if (tokens.isEmpty()) return ParseResult.NoMatch
        val matches = ynMatcher.find(tokens)
        if (matches.any { it.tag == YN.DONT_KNOW }) return ParseResult.NoMatch
        var yes = 0
        var no = 0
        var weak = 0
        var skipTagAt = -1
        for (m in matches) when (m.tag) {
            YN.NO -> if (m.start != skipTagAt) no++
            // "सही नहीं", "होता है नहीं": a yes word followed by a negator counts as no; "हाँ ना" is still yes.
            YN.YES, YN.WEAK_YES -> {
                val next = tokens.getOrNull(m.end)
                when {
                    next in tags -> { skipTagAt = m.end; if (m.tag == YN.YES) yes++ else weak++ }
                    next in postNeg -> { no++; skipTagAt = m.end }
                    m.tag == YN.YES -> yes++
                    else -> weak++
                }
            }
            YN.DONT_KNOW -> {}
        }
        val cov = coverage(tokens, matches)
        return when {
            yes > 0 && no == 0 -> ParseResult.Parsed(VoiceAnswer.YesNo(true), 0.7 + 0.3 * cov)
            no > 0 && yes == 0 -> ParseResult.Parsed(VoiceAnswer.YesNo(false), 0.7 + 0.3 * cov)
            yes > 0 && no > 0 -> ParseResult.Ambiguous(listOf(VoiceAnswer.YesNo(true), VoiceAnswer.YesNo(false)))
            weak > 0 -> ParseResult.Parsed(VoiceAnswer.YesNo(true), 0.5)
            else -> ParseResult.NoMatch
        }
    }

    // ---- numbers -------------------------------------------------------------------------------

    fun parseNumber(text: String, range: IntRange, allowUnknown: Boolean): ParseResult {
        val tokens = TextNormalizer.tokens(text)
        if (tokens.isEmpty()) return ParseResult.NoMatch
        val nums = extractNumbers(tokens).filter { it in range }.distinct()
        if (dontKnowMatcher.find(tokens).isNotEmpty() && nums.isEmpty()) {
            return if (allowUnknown) ParseResult.Parsed(VoiceAnswer.Number(null), 0.9) else ParseResult.NoMatch
        }
        return when (nums.size) {
            0 -> ParseResult.NoMatch
            1 -> ParseResult.Parsed(VoiceAnswer.Number(nums[0]), 0.9)
            else -> ParseResult.Ambiguous(nums.map { VoiceAnswer.Number(it) })
        }
    }

    /**
     * All numbers in the utterance: digits ("35", "1,500"), Hindi/English words ("पैंतीस", "thirty five"),
     * with सौ/hundred and हज़ार/thousand ("एक सौ दस" = 110, "दो हज़ार पाँच सौ" = 2500).
     */
    fun extractNumbers(text: String): List<Int> = extractNumbers(TextNormalizer.tokens(text))

    private sealed interface NumTok {
        data class Value(val v: Int, val digits: Boolean) : NumTok
        data object Hundred : NumTok
        data object Thousand : NumTok
        data object Other : NumTok
    }

    private fun classify(t: String): NumTok = when {
        t.all { it.isDigit() } -> t.toIntOrNull()?.let { NumTok.Value(it, true) } ?: NumTok.Other
        t in hundreds -> NumTok.Hundred
        t in thousands -> NumTok.Thousand
        else -> numberWords[t]?.let { NumTok.Value(it, false) } ?: NumTok.Other
    }

    private fun extractNumbers(rawTokens: List<String>): List<Int> {
        // "35साल" → "35", "साल"
        val tokens = rawTokens.flatMap { t -> Regex("\\d+|\\D+").findAll(t).map { it.value }.toList() }
        val out = mutableListOf<Int>()
        var total = 0
        var cur = 0
        var active = false
        var lastDigits = false
        fun flush() {
            if (active) out += total + cur
            total = 0; cur = 0; active = false
        }
        for (t in tokens) {
            when (val k = classify(t)) {
                is NumTok.Value -> {
                    val v = k.v
                    when {
                        !active -> { cur = v; active = true }
                        cur >= 100 && cur % 100 == 0 && v < 100 -> cur += v
                        total > 0 && cur == 0 -> cur = v
                        cur in 20..90 && cur % 10 == 0 && v in 1..9 && !(lastDigits && k.digits) -> cur += v
                        else -> { flush(); cur = v; active = true }
                    }
                    lastDigits = k.digits
                }
                NumTok.Hundred -> {
                    when {
                        !active -> { cur = 100; active = true }
                        cur == 0 -> cur = 100
                        cur in 1..99 -> cur *= 100
                        else -> { flush(); cur = 100; active = true }
                    }
                    lastDigits = false
                }
                NumTok.Thousand -> {
                    when {
                        !active -> { total = 1000; active = true }
                        cur in 1..999 -> { total += cur * 1000; cur = 0 }
                        cur == 0 -> total += 1000
                        else -> { flush(); total = 1000; active = true }
                    }
                    lastDigits = false
                }
                NumTok.Other -> flush()
            }
        }
        flush()
        return out
    }

    // ---- choices -------------------------------------------------------------------------------

    fun parseChoice(questionId: String, options: List<Any>, text: String, ctx: ChoiceContext): ParseResult {
        val lexQ = lex.choices[questionId] ?: return ParseResult.NoMatch
        val matcher = choiceMatchers.getValue(questionId)
        val tokens = TextNormalizer.tokens(text)
        if (tokens.isEmpty()) return ParseResult.NoMatch
        val byName = options.associateBy { optionName(it) }

        val matches = matcher.find(tokens).filterNot { negatedAfter(tokens, it) || negatedBefore(tokens, it) }
        if (matches.isNotEmpty()) {
            val counts = matches.groupingBy { it.tag }.eachCount().filterKeys { it in byName }
            if (counts.isNotEmpty()) {
                val topPriority = counts.keys.maxOf { lexQ.options.getValue(it).priority }
                val contenders = counts.filterKeys { lexQ.options.getValue(it).priority == topPriority }
                val best = contenders.values.max()
                val winners = contenders.filterValues { it == best }.keys.toList()
                return if (winners.size == 1) {
                    ParseResult.Parsed(VoiceAnswer.Choice(byName.getValue(winners[0])), 0.7 + 0.3 * coverage(tokens, matches))
                } else {
                    ParseResult.Ambiguous(winners.map { VoiceAnswer.Choice(byName.getValue(it)) })
                }
            }
        }

        val nums = extractNumbers(tokens)
        if (nums.isEmpty()) return ParseResult.NoMatch
        // "दो-तीन घंटे": take the larger figure, with lower confidence.
        val n = nums.max()
        val conf = if (nums.distinct().size == 1) 0.85 else 0.6
        val name = when (lexQ.numberMode) {
            VoiceLexicon.NumberMode.NONE -> null
            VoiceLexicon.NumberMode.RANGE ->
                lexQ.options.entries.firstOrNull { (_, o) -> o.range != null && n in o.range[0]..o.range[1] }?.key
            VoiceLexicon.NumberMode.BUDGET_CAPS -> when {
                n < 100 -> null // "दो" alone is not a rupee amount
                n <= ctx.budgetLowCap -> "LOW"
                n <= ctx.budgetMidCap -> "MID"
                else -> "HIGH"
            }
        }
        val opt = name?.let { byName[it] } ?: return ParseResult.NoMatch
        return ParseResult.Parsed(VoiceAnswer.Choice(opt), conf)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun negatedAfter(tokens: List<String>, m: PhraseMatcher.Match<*>) = tokens.getOrNull(m.end) in postNeg
    private fun negatedBefore(tokens: List<String>, m: PhraseMatcher.Match<*>) = tokens.getOrNull(m.start - 1) in preNeg

    private fun coverage(tokens: List<String>, matches: List<PhraseMatcher.Match<*>>): Double =
        if (tokens.isEmpty()) 0.0 else matches.sumOf { it.end - it.start }.toDouble() / tokens.size

    companion object {
        fun optionName(opt: Any): String = (opt as? Enum<*>)?.name ?: opt.toString()
    }
}

/** Greedy longest-phrase matcher over normalised tokens; phrases never overlap. */
class PhraseMatcher<T>(entries: List<Pair<String, T>>) {
    data class Match<T>(val start: Int, val end: Int, val tag: T)

    private val table = LinkedHashMap<List<String>, T>()
    private val maxLen: Int

    init {
        for ((phrase, tag) in entries) {
            val key = TextNormalizer.tokens(phrase)
            if (key.isNotEmpty() && key !in table) table[key] = tag
        }
        maxLen = table.keys.maxOfOrNull { it.size } ?: 0
    }

    fun find(tokens: List<String>): List<Match<T>> {
        val out = mutableListOf<Match<T>>()
        var i = 0
        while (i < tokens.size) {
            var hit: Match<T>? = null
            for (len in minOf(maxLen, tokens.size - i) downTo 1) {
                val tag = table[tokens.subList(i, i + len)]
                if (tag != null) { hit = Match(i, i + len, tag); break }
            }
            if (hit != null) { out += hit; i = hit.end } else i++
        }
        return out
    }
}

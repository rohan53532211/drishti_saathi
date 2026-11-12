package com.drishtisaathi.i18n

/**
 * String registry for the parallel tracks. Each track keeps its user-facing text in its own object
 * (one file each, so tracks never edit the same file), created with [tx] so StringsWordingTest checks it.
 * The Section 6 wording rules apply: never "prescription", "diagnosis", "power is" (or Hindi equivalents).
 */
object TxtRegistry {
    val all = mutableListOf<Txt>()

    /** Forces every track object to initialise so the wording test sees all of their strings. */
    fun loadAll(): List<Txt> {
        listOf(SCamera, SVision, SVoice, STryOn, SFlow, SAnamnesis, SBridge).forEach { it.hashCode() }
        return all
    }
}

/** Create and register a bilingual string. Use inside your track's object. */
fun tx(en: String, hi: String): Txt = Txt(en, hi).also { TxtRegistry.all += it }

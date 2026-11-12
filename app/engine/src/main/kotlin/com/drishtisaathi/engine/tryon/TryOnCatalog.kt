package com.drishtisaathi.engine.tryon

import com.drishtisaathi.engine.FrameCatalog

/**
 * The 30-frame demo catalogue is split in two assets so the Phase 1 catalogue (frames.json, F01–F10, which the
 * ranking tests are written against) stays unchanged: F11–F30 live in [MORE_FRAMES_ASSET], same schema.
 * The app can rank over [combine] of both.
 */
object TryOnCatalog {
    const val FRAMES_ASSET = "frames.json"
    const val MORE_FRAMES_ASSET = "frames/more_frames.json"

    /** Both catalogues as one; ids must stay unique. The comment of [base] is kept. */
    fun combine(base: FrameCatalog, more: FrameCatalog): FrameCatalog {
        val all = base.frames + more.frames
        require(all.map { it.id }.toSet().size == all.size) { "Duplicate frame ids across frame catalogues" }
        return FrameCatalog(base.comment, all)
    }

    fun parseCombined(framesJson: String, moreFramesJson: String): FrameCatalog =
        combine(FrameCatalog.parse(framesJson), FrameCatalog.parse(moreFramesJson))
}

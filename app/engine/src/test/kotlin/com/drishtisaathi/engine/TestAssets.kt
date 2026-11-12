package com.drishtisaathi.engine

import java.io.File

/** Loads the real JSON assets shipped in the app (path injected by engine/build.gradle.kts). */
object TestAssets {
    private val dir: File by lazy {
        File(System.getProperty("assetsDir") ?: "../app/src/main/assets").also {
            check(it.isDirectory) { "assets dir not found: ${it.absolutePath}" }
        }
    }

    fun text(name: String): String = File(dir, name).readText()

    val rules: RulesConfig by lazy { RulesConfig.parse(text("rules.json")) }
    val anthro: AnthroPriors by lazy { AnthroPriors.parse(text("anthro_priors.json")) }
}

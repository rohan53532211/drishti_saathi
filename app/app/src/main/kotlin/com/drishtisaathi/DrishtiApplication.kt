package com.drishtisaathi

import android.app.Application
import com.drishtisaathi.interview.QuestionBank

/** Points the question file at the app's assets (unit tests read it from the source tree). */
class DrishtiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuestionBank.loader = { assets.open(QuestionBank.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    }
}

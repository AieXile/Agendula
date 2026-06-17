package de.jeanlucmakiola.floret

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Registered as android:name=".FloretApp"
 * in AndroidManifest.xml. Hilt initializes its component graph here.
 */
@HiltAndroidApp
class FloretApp : Application()

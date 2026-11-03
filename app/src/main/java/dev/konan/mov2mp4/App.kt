package dev.konan.mov2mp4

import android.app.Application
import dev.konan.mov2mp4.data.Converter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Converter.init(this)
    }

    companion object {
        /** True while the main screen is on display. */
        @Volatile var visible = false
    }
}

package com.taptype.taptypepro

import android.app.Application
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.Settings

class TapTypeProApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        Settings.init(this)
        DebugLog.i("App", "TapType Pro started")
    }
}

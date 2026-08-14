package com.taptype.taptypepro

import android.app.Application
import com.taptype.taptypepro.util.DebugLog

class TapTypeProApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.i("App", "TapType Pro started")
    }
}

package com.protocol

import android.app.Application
import com.protocol.notif.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(AppVisibility)
    }
}

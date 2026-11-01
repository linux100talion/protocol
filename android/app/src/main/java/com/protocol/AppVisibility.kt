package com.protocol

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Видно ли сейчас окно приложения (любая наша Activity между onStart и onStop).
 * Нужно сервису: если окно на экране, первое уведомление шага лишнее,
 * вопрос и так появится на экране.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0

    val visible: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { if (started > 0) started-- }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

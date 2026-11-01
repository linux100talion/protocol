package com.protocol.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.protocol.databinding.ActivityStepBinding
import com.protocol.engine.Mode
import com.protocol.engine.Screen
import com.protocol.service.EngineHolder

/**
 * Экран шага по графику. Рисует Screen из движка. SPEC 6.2.
 * Назад не работает. Закрывается сам, когда шага нет.
 * Процессы здесь не показываются: они идут на главном экране.
 */
class StepActivity : AppCompatActivity() {

    private lateinit var b: ActivityStepBinding
    private var wasStepReason = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStepBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* назад запрещён */ }
        })
        EngineHolder.version.observe(this) { render() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val engine = EngineHolder.engine ?: run { finishAndMaybeShowStopped(); return }
        val scr = engine.stepScreen() ?: run { finishAndMaybeShowStopped(); return }
        if (scr is Screen.Reason) wasStepReason = true

        b.titleText.text = scr.title
        b.questionText.text = scr.text
        ScreenBinder.bind(this, scr, null, b.answerInput, b.buttons, large = true)
    }

    private fun finishAndMaybeShowStopped() {
        if (wasStepReason && EngineHolder.engine?.state?.mode == Mode.STOPPED) {
            startActivity(Intent(this, StopActivity::class.java))
        }
        finish()
    }
}

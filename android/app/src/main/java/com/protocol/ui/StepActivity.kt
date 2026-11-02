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
 * Процессы идут на главном экране; исключение: вопрос таймера процесса
 * (EXTRA_PROC_ID) открывается здесь поверх блокировки через fullScreenIntent
 * и закрывается, как только вопрос отвечен.
 */
class StepActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROC_ID = "proc_id"
    }

    private lateinit var b: ActivityStepBinding
    private var procId: String? = null
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
        procId = intent.getStringExtra(EXTRA_PROC_ID)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* назад запрещён */ }
        })
        EngineHolder.version.observe(this) { render() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procId = intent.getStringExtra(EXTRA_PROC_ID)
        render()
    }

    private fun render() {
        val engine = EngineHolder.engine ?: run { finishAndMaybeShowStopped(); return }
        var target: String? = null
        var scr: Screen? = engine.stepScreen()
        if (scr == null) {
            // Вопрос таймера процесса. Ожидание (после «Нет») здесь не показываем.
            target = procId
            scr = target?.let { engine.procScreen(it) }?.takeUnless { it is Screen.Wait }
        }
        if (scr == null) { finishAndMaybeShowStopped(); return }
        if (target == null && scr is Screen.Reason) wasStepReason = true

        b.titleText.text = scr.title
        b.questionText.text = scr.text
        ScreenBinder.bind(this, scr, target, b.answerInput, b.buttons, large = true)
    }

    private fun finishAndMaybeShowStopped() {
        if (wasStepReason && EngineHolder.engine?.state?.mode == Mode.STOPPED) {
            startActivity(Intent(this, StopActivity::class.java))
        }
        finish()
    }
}

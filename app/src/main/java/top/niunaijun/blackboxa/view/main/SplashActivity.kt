package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.base.BaseActivity
import top.niunaijun.blackboxa.view.sakuga.SakugaFX

/**
 * 启动页：Sakuga 漫画风开场，倒计时 3 秒进主页。
 * 每秒数字弹一下 + 一朵小冲击星；点“跳过”直接进。
 */
class SplashActivity : BaseActivity() {

    private var entered = false
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val numberView = findViewById<TextView>(R.id.splash_countdown)
        val skipView = findViewById<TextView>(R.id.splash_skip)
        // 跳过：按压扁一下，松手直接进主页
        SakugaFX.attachPress(skipView) { _, _, _ -> enterMain() }
        // 数字也能点：按一下弹一下
        SakugaFX.attachPress(numberView) { _, _, _ -> }
        numberView.text = "3"
        // 刚进页面还没量好位置，等一帧再弹，不然冲击星炸在左上角
        numberView.post { if (!entered) popNumber(numberView) }
        timer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val n = ((millisUntilFinished + 999) / 1000).toInt()
                if (n in 1..3) {
                    numberView.text = n.toString()
                    popNumber(numberView)
                }
            }

            override fun onFinish() = enterMain()
        }.start()
    }

    /** 数字弹一下：从 1.3 倍缩回 + 一朵小冲击星 */
    private fun popNumber(view: TextView) {
        view.scaleX = 1.3f
        view.scaleY = 1.3f
        view.animate().scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator()).start()
        SakugaFX.burstAtCenter(view)
    }

    private fun enterMain() {
        if (entered) return
        entered = true
        timer?.cancel()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}

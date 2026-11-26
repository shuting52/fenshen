package top.niunaijun.blackboxa.view.main

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.base.BaseActivity
import top.niunaijun.blackboxa.view.sakuga.SakugaCloudView

/**
 * 启动页：冲击星旋转呼吸、标题逐字弹出、小星星漂浮、云朵起伏。
 * 2 秒后自动进主页，没有倒计时、没有 X。
 */
class SplashActivity : BaseActivity() {

    private var entered = false
    private var timer: CountDownTimer? = null
    private val animators = ArrayList<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        // 2 秒后自动进主页，没有倒计时、没有 X
        timer = object : CountDownTimer(2000, 1000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() = enterMain()
        }.start()

        // 冲击星：慢慢转 + 呼吸
        val burst = findViewById<ImageView>(R.id.splash_burst)
        animators += ObjectAnimator.ofFloat(burst, "rotation", 0f, 360f).apply {
            duration = 14000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        animators += ObjectAnimator.ofFloat(burst, "scaleX", 1f, 1.07f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        animators += ObjectAnimator.ofFloat(burst, "scaleY", 1f, 1.07f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        // 标题逐字弹出：淑 / 婷 / 分 / 身，一个接一个弹出来
        val titleRow = findViewById<LinearLayout>(R.id.splash_title_row)
        for (i in 0 until titleRow.childCount) {
            val letter = titleRow.getChildAt(i)
            letter.scaleX = 0f
            letter.scaleY = 0f
            letter.animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setStartDelay((i * 90).toLong())
                .setInterpolator(OvershootInterpolator()).start()
        }

        // 小星星上下漂浮（各不相同的时间）
        bob(findViewById(R.id.splash_star_1), 1600, 0)
        bob(findViewById(R.id.splash_star_2), 2100, 300)
        bob(findViewById(R.id.splash_star_3), 1800, 600)

        // 底部云朵起伏
        findViewById<SakugaCloudView>(R.id.splash_cloud).startBob()
    }

    /** 上下漂浮：translationY 0 → -10dp → 0，无限循环 */
    private fun bob(view: ImageView, duration: Long, startDelay: Long) {
        animators += ObjectAnimator.ofFloat(view, "translationY", 0f, -10f * resources.displayMetrics.density).apply {
            this.duration = duration
            this.startDelay = startDelay
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
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
        animators.forEach { it.cancel() }
        super.onDestroy()
    }
}

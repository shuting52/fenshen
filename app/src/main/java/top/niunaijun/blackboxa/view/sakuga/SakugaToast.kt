// Sakuga 提示：白纸卡片从侧面拉成一条烟一样 streak 进来，落下时扬起一小团尘、
// 落一个ズザッ，停 2.6 秒，离场时缩小消失（不淡出）。
// 全 App 的 toast 都走这里（ToastEx.kt），调用处不用改。
package top.niunaijun.blackboxa.view.sakuga

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import top.niunaijun.blackboxa.R
import java.lang.ref.WeakReference

object SakugaToast {

    private var currentActivity: WeakReference<Activity>? = null
    private var toastView: LinearLayout? = null
    private var hideRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在 App.onCreate 里调一次，记下当前在前台的 Activity。 */
    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }

            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun show(context: Context, msg: String) {
        if (msg.isEmpty()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, msg) }
            return
        }
        val activity = SakugaFX.activityOf(context) ?: currentActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            return
        }
        showIn(activity, msg)
    }

    private fun showIn(activity: Activity, msg: String) {
        dismissNow()
        val density = activity.resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(activity, R.drawable.bg_sakuga_card)
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        }
        val tv = TextView(activity).apply {
            text = msg
            setTextColor(SakugaFX.INK)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(tv)
        val decor = activity.window.decorView as ViewGroup
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(96f)
            leftMargin = dp(24f)
            rightMargin = dp(24f)
        }
        decor.addView(container, lp)
        toastView = container
        container.post {
            // 像一条烟一样拉进来：横向拉长 + 从右边滑入
            container.pivotX = container.width.toFloat()
            container.scaleX = 2.2f
            container.translationX = 120f * density
            container.animate()
                .scaleX(1f)
                .translationX(0f)
                .setDuration(260)
                .setInterpolator(OvershootInterpolator())
                .withEndAction {
                    // 落地：扬尘 + ズザッ
                    val loc = IntArray(2)
                    container.getLocationOnScreen(loc)
                    val fxv = SakugaFX.fxViewFor(container)
                    if (fxv != null) {
                        val floc = IntArray(2)
                        fxv.getLocationOnScreen(floc)
                        val cx = loc[0] + container.width / 2f - floc[0]
                        val top = loc[1] - floc[1]
                        SakugaFX.puff(fxv, cx, top.toFloat(), 8f, 0.6f)
                        SakugaFX.sfx(fxv, SakugaFX.SFX_ZUZA, cx, top.toFloat() - 24f * density, 18f, dotted = true)
                    }
                }
                .start()
        }
        hideRunnable = Runnable { leave() }
        mainHandler.postDelayed(hideRunnable!!, 2600)
    }

    /** 离场：缩小消失，不淡出。 */
    private fun leave() {
        val v = toastView ?: return
        v.animate()
            .scaleX(0.05f)
            .scaleY(0.05f)
            .setDuration(240)
            .withEndAction { dismissNow() }
            .start()
    }

    private fun dismissNow() {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null
        val v = toastView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        toastView = null
    }
}

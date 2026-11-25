// 一朵暴风雨云：三个圆瓣 + 底座，两色 + 一条线（跟 SakugaFX 的 blob() 一个画法）。
// 骑在下载进度条的前沿，也用在加载弹窗里。
package top.niunaijun.blackboxa.view.sakuga

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class SakugaCloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var bobAnim: ObjectAnimator? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SakugaFX.LINE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SakugaFX.SHADE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SakugaFX.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((48 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((32 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val path = Path()
        // 三个圆瓣
        path.addCircle(w * 0.28f, h * 0.62f, h * 0.30f, Path.Direction.CW)
        path.addCircle(w * 0.50f, h * 0.45f, h * 0.36f, Path.Direction.CW)
        path.addCircle(w * 0.72f, h * 0.62f, h * 0.30f, Path.Direction.CW)
        // 底座，把三个圆瓣连成一朵云
        path.addRect(w * 0.06f, h * 0.55f, w * 0.94f, h * 0.98f, Path.Direction.CW)
        // 先描边，再裁剪填阴影色，最后向上左偏移填白
        canvas.save()
        canvas.drawPath(path, strokePaint)
        canvas.restore()
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(0f, 0f, w, h, shadePaint)
        canvas.save()
        canvas.translate(-h * 0.06f, -h * 0.09f)
        canvas.drawPath(path, fillPaint)
        canvas.restore()
        canvas.restore()
    }

    /** 上下浮动（加载时用）。 */
    fun startBob() {
        if (bobAnim != null) return
        bobAnim = ObjectAnimator.ofFloat(this, "translationY", 0f, -6f * density, 0f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun stopBob() {
        bobAnim?.cancel()
        bobAnim = null
        translationY = 0f
    }

    override fun onDetachedFromWindow() {
        stopBob()
        super.onDetachedFromWindow()
    }
}

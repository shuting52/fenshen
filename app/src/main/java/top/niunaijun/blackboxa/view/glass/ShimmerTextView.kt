package top.niunaijun.blackboxa.view.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * 流光文字：绿→蓝→靛渐变里一道白光从左到右不停扫过，
 * 就是网页 CSS 里 background-clip:text 那种流光字效果，安置在原生 View 里实现。
 */
class ShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val gradientColors = intArrayOf(
        Color.parseColor("#16A34A"),
        Color.parseColor("#0284C7"),
        Color.parseColor("#4F46E5"),
        Color.parseColor("#BAE6FD"),
        Color.parseColor("#16A34A")
    )
    private val gradientPositions = floatArrayOf(0f, 0.35f, 0.6f, 0.8f, 1f)
    private var gradient: LinearGradient? = null
    private val shaderMatrix = Matrix()
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            gradient = LinearGradient(
                0f, 0f, w * 2f, 0f,
                gradientColors, gradientPositions,
                Shader.TileMode.REPEAT
            )
            paint.shader = gradient
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val w = width.toFloat()
                if (w > 0) {
                    shaderMatrix.setTranslate(-(it.animatedValue as Float) * w * 2f, 0f)
                    gradient?.setLocalMatrix(shaderMatrix)
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

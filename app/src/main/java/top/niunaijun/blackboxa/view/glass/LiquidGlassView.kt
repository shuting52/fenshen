package top.niunaijun.blackboxa.view.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import top.niunaijun.blackboxa.util.Resolution

/**
 * Views 原生液态玻璃。
 *
 * 真透镜着色器（Backdrop 的 RuntimeShader）要 Android 13，Y77 是 Android 12，
 * 系统里根本没有这个类，Compose 里嵌进来也跑不了。
 * 这是在 Android 12 上能跑的透镜做法，三个要素和真液态玻璃一样：
 *  1. 模糊：取预模糊过的壁纸；
 *  2. 折射：网格把背后的图向外推，越靠近边缘放大越强，像透镜一样鼓起来；
 *  3. 高光：顶部一道亮光 + 一圈亮边。
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cornerRadius = Resolution.convertDpToPixel(28f, context)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 玻璃面调浅到 0.25：太黑会把透镜盖住，背景必须透出来才像玻璃 */
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59FFFFFF")
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Resolution.convertDpToPixel(1.5f, context)
        color = Color.argb(140, 255, 255, 255)
    }

    /** 内圈第二道亮边：透镜边缘折得最厉害的地方最亮 */
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Resolution.convertDpToPixel(1f, context)
        color = Color.argb(45, 255, 255, 255)
    }

    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clipPath = Path()
    private val rectF = RectF()

    private val meshCols = 24
    private val meshRows = 24
    private val verts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
        specularPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.5f,
            Color.argb(77, 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val bmp = GlassWallpaper.get(context)
        if (bmp == null) {
            // 壁纸还没准备好：先画玻璃面，等准备好后下一帧再画透镜
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, tintPaint)
            postInvalidateDelayed(400)
            return
        }

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val vx = loc[0]
        val vy = loc[1]
        // 视图在屏幕上的可见部分，与壁纸相交的那一块
        val sx0 = vx.coerceIn(0, bmp.width)
        val sy0 = vy.coerceIn(0, bmp.height)
        val sx1 = (vx + width).coerceIn(0, bmp.width)
        val sy1 = (vy + height).coerceIn(0, bmp.height)
        if (sx1 > sx0 && sy1 > sy0) {
            val crop = Bitmap.createBitmap(bmp, sx0, sy0, sx1 - sx0, sy1 - sy0)
            // 裁切块左上角在视图本地坐标里的偏移
            val offX = (sx0 - vx).toFloat()
            val offY = (sy0 - vy).toFloat()
            val cw = crop.width.toFloat()
            val ch = crop.height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val maxR = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
            // 网格透镜：以卡片中心为准，越靠近边缘推得越远（折射），中心几乎不动。
            // 2.3.7 只推到 1.22，糊图上根本看不出来；这次推到 1.5，边缘折得狠才看得见。
            var k = 0
            for (j in 0..meshRows) {
                for (i in 0..meshCols) {
                    val bx = offX + cw * i / meshCols
                    val by = offY + ch * j / meshRows
                    val dx = bx - cx
                    val dy = by - cy
                    val r = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / maxR
                    val f = 1f + 0.5f * Math.pow(r.toDouble(), 2.5).toFloat()
                    verts[k++] = cx + dx * f
                    verts[k++] = cy + dy * f
                }
            }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmapMesh(crop, meshCols, meshRows, verts, 0, null, 0, paint)
            canvas.restore()
        }

        // 玻璃面 + 顶部高光 + 两圈亮边
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, tintPaint)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, specularPaint)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, rimPaint)
        val inset = Resolution.convertDpToPixel(4f, context)
        val innerRect = RectF(inset, inset, w - inset, h - inset)
        val innerRadius = (cornerRadius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerRimPaint)
    }
}

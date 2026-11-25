package top.niunaijun.blackboxa.view.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.DisplayMetrics
import eightbitlab.com.blurview.RenderScriptBlur
import top.niunaijun.blackboxa.R

/**
 * 整屏壁纸：按屏幕尺寸 centerCrop，只轻微模糊一次，全 App 共用。
 * 卡片上的透镜就是从这张图里取自己背后的那一块来放大。
 * 注意：不能像 2.3.7 那样模糊到 12——图糊成一片就没东西可折，透镜就看不见了。
 * 透镜要折的是清晰的特征，所以这里只做 3 的轻微柔化。
 */
object GlassWallpaper {

    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var preparing = false

    private val lock = Any()

    /** 启动时在后台线程预热一次，卡片画出来就是透镜，不用等。 */
    fun prepare(context: Context) {
        get(context.applicationContext)
    }

    fun get(context: Context): Bitmap? {
        cached?.let { return it }
        if (!preparing) {
            preparing = true
            Thread {
                try {
                    build(context.applicationContext)
                } finally {
                    preparing = false
                }
            }.start()
        }
        return null
    }

    private fun build(appCtx: Context): Bitmap? {
        synchronized(lock) {
            cached?.let { return it }
            val dm: DisplayMetrics = appCtx.resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            if (sw <= 0 || sh <= 0) return null
            val src = BitmapFactory.decodeResource(appCtx.resources, R.drawable.bg_stage)
                ?: return null
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            // centerCrop：和 ImageView 的 centerCrop 同一套算法，保证卡片取到的位置和看到的一致
            val scale = maxOf(sw.toFloat() / src.width, sh.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            val left = (sw - dw) / 2f
            val top = (sh - dh) / 2f
            canvas.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), null)
            src.recycle()
            // 轻微柔化即可；模糊失败也别空手而归，用原图兜底，透镜照样画
            val blurred = try {
                RenderScriptBlur(appCtx).blur(out, 3f)
            } catch (e: Exception) {
                null
            } ?: out
            if (blurred != out) out.recycle()
            cached = blurred
            return blurred
        }
    }
}

// Sakuga VFX —— 原生复刻。
//
// 来源：finktheartist/sakuga-vfx（MIT）。那是一套 Web Components：效果画在页面上的一块
// canvas 上，开关是 43 帧手绘图，拟声字是描摹好的路径——这些都跑不到原生 Views 工程里。
// 这里只搬它的设计语言与反应规则，用安卓自己的 Canvas + 弹簧重做：
//   - 调色板与 --sk-* 变量原值：白纸 #FFFFFF、墨 #1D1A4A、薰衣草 #B8B3FF、紫线 #5A4FD8、
//     主紫 #8F8DF7、粉红 #FF5FA8、轨道 #C9CBE0、次字 #8580B0
//   - 两色 + 一条线：每个图形都是浅色面 + 一块平色阴影 + 描边；没有渐变，没有模糊
//   - 效果离场靠缩小/变细，透明度永远不变
//   - 挤压保体积：压扁时变宽，拉长时变窄；弹簧（k=300, d=20）而不是缓动
//   - 粉红只给点缀（楔形、针、勾）；平时控件是素的，什么都不画
//   - 一次反应最多一个拟声字，落在命中那一帧：咚！（确认）、咔哒（开关）、
//     唰（勾选/提示）、砰（切页）、轰隆隆（加载/进度）
package top.niunaijun.blackboxa.view.sakuga

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import top.niunaijun.blackboxa.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object SakugaFX {

    // ── 调色板（core.js 里 P 与 VARS 的原值） ──
    val INK = Color.parseColor("#1D1A4A")
    val FILL = Color.WHITE
    val SHADE = Color.parseColor("#B8B3FF")
    val LINE = Color.parseColor("#5A4FD8")
    val ACCENT = Color.parseColor("#FF5FA8")
    val PRI = Color.parseColor("#8F8DF7")
    val TRACK = Color.parseColor("#C9CBE0")
    val DIM = Color.parseColor("#8580B0")

    // ── 拟声字（core.js 的六个词，这里只用得到的五个） ──
    const val SFX_DON = "咚！"
    const val SFX_KACHI = "咔哒"
    const val SFX_ZUZA = "唰"
    const val SFX_PON = "砰"
    const val SFX_GOGO = "轰隆隆"

    private const val TAU = (PI * 2).toFloat()

    // ── 弹簧（core.js Spring 原样：k=300, d=20，8ms 子步） ──
    class Spring(var x: Float = 0f, val k: Float = 300f, val d: Float = 20f) {
        var v = 0f
        var to = x

        fun step(dt: Float): Float {
            var rem = dt
            while (rem > 0f) {
                val h = min(0.008f, rem)
                v += (-k * (x - to) - d * v) * h
                x += v * h
                rem -= h
            }
            return x
        }

        fun isAtRest() = abs(x - to) < 0.002f && abs(v) < 0.002f
    }

    // ── 缓动（core.js 的 out / inn / back） ──
    private fun out(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun inn(t: Float) = t * t * t

    private fun back(t: Float): Float {
        val u = t - 1f
        return 1f + 2.70158f * u * u * u + 1.70158f * u * u
    }

    private fun clamp01(t: Float) = t.coerceIn(0f, 1f)

    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    private fun rnd(a: Float) = Random.nextFloat() * a

    // ── 覆盖层：一个挂在窗口最上层的透明 View，只画效果，不接手势 ──
    class FXView(context: Context) : View(context) {

        private class Fx(val life: Float, val draw: (Canvas, Float, Float) -> Unit) {
            var t = 0f
        }

        private class SpringAnim(
            val view: View,
            val sx: Spring,
            val sy: Spring,
            val onDone: (() -> Unit)?
        )

        private val effects = ArrayList<Fx>()
        private val springs = ArrayList<SpringAnim>()
        private var running = false
        private var lastMs = 0L
        private val density = resources.displayMetrics.density

        init {
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
        }

        fun dp(v: Float) = v * density

        fun addEffect(life: Float, draw: (Canvas, Float, Float) -> Unit) {
            effects.add(Fx(life, draw))
            start()
        }

        fun addSpring(view: View, sx: Spring, sy: Spring, onDone: (() -> Unit)? = null) {
            springs.removeAll { it.view === view }
            springs.add(SpringAnim(view, sx, sy, onDone))
            start()
        }

        fun cancelSprings(view: View) {
            springs.removeAll { it.view === view }
        }

        private val frameCallback = Choreographer.FrameCallback { ms -> onFrame(ms) }

        private fun start() {
            if (running) return
            running = true
            lastMs = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun onFrame(ms: Long) {
            if (!running) return
            val dt = if (lastMs == 0L) 0.016f else ((ms - lastMs) / 1000f).coerceIn(0f, 0.034f)
            lastMs = ms
            val sit = springs.iterator()
            while (sit.hasNext()) {
                val s = sit.next()
                s.sx.step(dt)
                s.sy.step(dt)
                s.view.scaleX = s.sx.x
                s.view.scaleY = s.sy.x
                if (s.sx.isAtRest() && s.sy.isAtRest()) {
                    s.view.scaleX = s.sx.to
                    s.view.scaleY = s.sy.to
                    sit.remove()
                    s.onDone?.invoke()
                }
            }
            val eit = effects.iterator()
            while (eit.hasNext()) {
                val e = eit.next()
                e.t += dt
                if (e.t >= e.life) eit.remove()
            }
            invalidate()
            if (effects.isEmpty() && springs.isEmpty()) {
                running = false
                return
            }
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (e in effects) {
                val k = clamp01(e.t / e.life)
                canvas.save()
                e.draw(canvas, k, e.t)
                canvas.restore()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            running = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            effects.clear()
            springs.clear()
        }
    }

    // ── 挂接 ──

    /** 把效果层挂到一个窗口的 decor 上（Activity 在 BaseActivity 里挂，弹窗自己挂）。 */
    fun attachToWindow(window: Window?) {
        val decor = window?.decorView as? ViewGroup ?: return
        if (decor.findViewById<View>(R.id.sakuga_fx_view) != null) return
        val fx = FXView(window.context)
        fx.id = R.id.sakuga_fx_view
        decor.addView(
            fx,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** 一个 View 所在窗口的效果层（没有就返回 null）。 */
    fun fxViewFor(view: View): FXView? {
        val root = view.rootView as? ViewGroup ?: return null
        return root.findViewById(R.id.sakuga_fx_view)
    }

    fun activityOf(context: Context?): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    // ── 画笔 ──

    private fun strokePaint(color: Int, widthPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = widthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /** 两色 + 一条线：先描边，再裁剪填阴影色，最后向上左偏移填浅色——跟 core.js 的 shape() 一个画法。 */
    private fun twoTone(canvas: Canvas, path: Path, lwPx: Float, offPx: Float) {
        canvas.save()
        canvas.drawPath(path, strokePaint(LINE, lwPx * 2))
        canvas.restore()
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(-10000f, -10000f, 20000f, 20000f, fillPaint(SHADE))
        canvas.save()
        canvas.translate(-offPx * 0.7f, -offPx)
        canvas.drawPath(path, fillPaint(FILL))
        canvas.restore()
        canvas.restore()
    }

    private fun starPath(
        cx: Float,
        cy: Float,
        r: Float,
        inner: FloatArray,
        hollow: Float,
        rot: Float
    ): Path {
        val p = Path()
        val n = inner.size
        for (i in 0 until n) {
            val a = rot + (i.toFloat() / n) * TAU
            val m = inner[i]
            // 奇数顶点是内点，随 hollow 长大，尖就变细；夹到 1.9 防止翻折
            val rr = r * if (i % 2 == 1) min(m * hollow, 1.9f) else m
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    // ── 效果（坐标都是 FXView 自己的坐标系） ──

    /** 冲击星：粉红针 + 薰衣草大星 + 白小星，尖变细离场。 */
    fun burst(fx: FXView?, x: Float, y: Float, rDp: Float = 44f) {
        if (fx == null) return
        val r = fx.dp(rDp)
        val spikes = 13
        val rot = rnd(TAU)
        val inner = FloatArray(spikes * 2) { i ->
            if (i % 2 == 1) rnd(0.34f, 0.5f) else rnd(0.75f, 1.15f)
        }
        val pins = Array(8) {
            Triple(it / 8f * TAU + rnd(0.5f), rnd(1.25f, 1.75f), rnd(0.03f, 0.055f))
        }
        fx.addEffect(0.34f) { canvas, k, _ ->
            val R = r * out(clamp01(k / 0.3f))
            val hollow = 1f + inn(clamp01((k - 0.35f) / 0.65f)) * 1.3f
            val r0 = R * 0.6f * min(1.9f, hollow)
            // 粉红针
            val pinPath = Path()
            for ((a, l, w) in pins) {
                val len = R * l
                if (len <= r0) continue
                pinPath.moveTo(x + cos(a - w) * r0, y + sin(a - w) * r0)
                pinPath.lineTo(x + cos(a) * len, y + sin(a) * len)
                pinPath.lineTo(x + cos(a + w) * r0, y + sin(a + w) * r0)
                pinPath.close()
            }
            canvas.drawPath(pinPath, fillPaint(ACCENT))
            // 大星：薰衣草面 + 紫描边
            val big = starPath(x, y, R * 1.16f, inner, hollow, rot + 0.19f)
            canvas.drawPath(big, fillPaint(SHADE))
            canvas.drawPath(big, strokePaint(LINE, fx.dp(1.2f)))
            // 小星：白面向上左偏移（两色）
            val small = starPath(x, y, R * 0.84f, inner, hollow, rot)
            canvas.save()
            canvas.translate(-fx.dp(3f) * 0.7f, -fx.dp(3f))
            canvas.drawPath(small, fillPaint(FILL))
            canvas.restore()
            canvas.drawPath(small, strokePaint(LINE, fx.dp(1.4f)))
        }
    }

    /** 一团烟：几个圆瓣胀开、翻滚、缩小离场。 */
    fun puff(fx: FXView?, x: Float, y: Float, sizeDp: Float = 13f, life: Float = 0.8f) {
        if (fx == null) return
        val size = fx.dp(sizeDp)
        val n = 5
        val lobes = ArrayList<FloatArray>()
        lobes.add(floatArrayOf(0f, 0f, size, 0f, 1f))
        for (i in 0 until n) {
            lobes.add(
                floatArrayOf(
                    (i.toFloat() / n) * TAU + rnd(0.6f),
                    rnd(0.25f, 0.9f) * size,
                    rnd(0.55f, 1f) * size,
                    rnd(TAU),
                    rnd(0.6f, 1.3f)
                )
            )
        }
        fx.addEffect(life) { canvas, k, t ->
            val grow = back(clamp01(t / 0.22f))
            val die = 1f - inn(clamp01((k - 0.55f) / 0.45f))
            val px = x
            val py = y - 18f * fx.dp(1f) * out(k) * life
            val path = Path()
            var firstR = size
            for (l in lobes) {
                val d = l[1] * (0.5f + out(k) * 0.9f)
                val cx = px + cos(l[0] + t * 0.9f * l[4]) * d
                val cy = py + sin(l[0] + t * 0.9f * l[4]) * d - out(k) * 5f * l[4]
                val rr = l[2] * grow * die * (1f + sin(t * 9f + l[3]) * 0.05f)
                if (rr > 0.3f) {
                    path.addCircle(cx, cy, rr, Path.Direction.CW)
                    firstR = rr
                }
            }
            if (path.isEmpty) return@addEffect
            // 描边 + 裁剪填阴影 + 向上左偏移填白（跟 blob() 一个画法）
            canvas.save()
            canvas.drawPath(path, strokePaint(LINE, fx.dp(1.5f) * 2))
            canvas.restore()
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(-10000f, -10000f, 20000f, 20000f, fillPaint(SHADE))
            canvas.save()
            canvas.translate(-firstR * 0.2f * 1.5f * 0.5f, -firstR * 0.26f * 1.5f)
            canvas.drawPath(path, fillPaint(FILL))
            canvas.restore()
            canvas.restore()
        }
    }

    /** 水晶碎片：几片带棱的碎片飞出去、翻滚、落下、缩小。 */
    fun shards(fx: FXView?, x: Float, y: Float, n: Int = 7, sizeDp: Float = 5f) {
        if (fx == null) return
        val size = fx.dp(sizeDp)
        val speed = fx.dp(190f)
        val grav = fx.dp(420f)
        for (i in 0 until n) {
            val a = -PI.toFloat() / 2 + rnd(-PI.toFloat() / 2, PI.toFloat() / 2)
            val v = speed * rnd(0.5f, 1.15f)
            val s = size * rnd(0.6f, 1.4f)
            val spin = rnd(-7f, 7f)
            val pts = arrayOf(
                floatArrayOf(-1.4f, 0.1f), floatArrayOf(-0.6f, -0.7f),
                floatArrayOf(1.2f, -0.45f), floatArrayOf(1.5f, 0.15f),
                floatArrayOf(0.4f, 0.75f)
            ).map { floatArrayOf(it[0] * rnd(0.8f, 1.2f), it[1] * rnd(0.8f, 1.2f)) }
            val life = 0.75f * rnd(0.7f, 1f)
            fx.addEffect(life) { canvas, k, t ->
                val px = x + cos(a) * v * t
                val py = y + sin(a) * v * t + grav * t * t
                val sc = s * (1f - inn(clamp01((k - 0.6f) / 0.4f)))
                if (sc <= 0.5f) return@addEffect
                val p = Path()
                pts.forEachIndexed { q, pt ->
                    val ux = pt[0] * sc
                    val uy = pt[1] * sc
                    if (q == 0) p.moveTo(ux, uy) else p.lineTo(ux, uy)
                }
                p.close()
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate((spin * t + a) * 180f / PI.toFloat())
                twoTone(canvas, p, fx.dp(1.1f), sc * 0.45f)
                if (sc > fx.dp(2.5f)) {
                    canvas.drawLine(
                        pts[1][0] * sc, pts[1][1] * sc,
                        pts[4][0] * sc, pts[4][1] * sc,
                        strokePaint(LINE, fx.dp(0.9f))
                    )
                }
                canvas.restore()
            }
        }
    }

    /** 一道斩击：粉红新月扫过去，头跑在前面、尾巴追上来。 */
    fun slash(fx: FXView?, x: Float, y: Float, lenDp: Float = 110f, ang: Float = -0.5f) {
        if (fx == null) return
        val len = fx.dp(lenDp)
        val r = len * 0.9f
        val cx = x - cos(ang - PI.toFloat() / 2) * r * 0.86f
        val cy = y - sin(ang - PI.toFloat() / 2) * r * 0.86f
        val a0 = ang - PI.toFloat() / 2 - 0.62f
        val sweep = 1.24f
        val thick = fx.dp(15f)
        fx.addEffect(0.42f) { canvas, k, _ ->
            val head = out(clamp01(k / 0.42f))
            val tail = inn(clamp01((k - 0.22f) / 0.78f))
            if (head - tail < 0.01f) return@addEffect
            fun cres(t: Float): Path {
                val p = Path()
                val n = 26
                for (i in 0..n) {
                    val u = tail + (head - tail) * (i.toFloat() / n)
                    val a = a0 + sweep * u
                    val px = cx + cos(a) * r
                    val py = cy + sin(a) * r
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                for (i in n downTo 0) {
                    val q = i.toFloat() / n
                    val u = tail + (head - tail) * q
                    val a = a0 + sweep * u
                    val rr = r - t * sin(q * PI.toFloat()).let { s -> Math.pow(s.toDouble(), 0.7).toFloat() }
                    p.lineTo(cx + cos(a) * rr, cy + sin(a) * rr)
                }
                p.close()
                return p
            }
            val c = cres(thick)
            canvas.drawPath(c, fillPaint(ACCENT))
            canvas.drawPath(cres(thick * 0.42f), fillPaint(FILL))
            canvas.drawPath(c, strokePaint(LINE, fx.dp(1.3f)))
        }
    }

    /** 烟圈：一个椭圆环胀开、变细。 */
    fun ring(fx: FXView?, x: Float, y: Float, rxDp: Float = 40f, ryDp: Float = 12f) {
        if (fx == null) return
        val rx = fx.dp(rxDp)
        val ry = fx.dp(ryDp)
        val w = fx.dp(5f)
        fx.addEffect(0.5f) { canvas, k, _ ->
            val s = out(k)
            val lw = w * (1f - k)
            canvas.drawOval(
                x - rx * (0.3f + s), y - ry * (0.3f + s),
                x + rx * (0.3f + s), y + ry * (0.3f + s),
                strokePaint(LINE, lw + fx.dp(2.4f))
            )
            if (lw > 0.3f) {
                canvas.drawOval(
                    x - rx * (0.3f + s), y - ry * (0.3f + s),
                    x + rx * (0.3f + s), y + ry * (0.3f + s),
                    strokePaint(FILL, lw)
                )
            }
        }
    }

    /** 水花：几瓣水舌从一条线上升起、落回，带水滴。 */
    fun splash(fx: FXView?, x: Float, y: Float, wDp: Float = 34f, hDp: Float = 26f) {
        if (fx == null) return
        val w = fx.dp(wDp)
        val h = fx.dp(hDp)
        val n = 7
        val ts = Array(n) { i ->
            val u = i.toFloat() / (n - 1) - 0.5f
            floatArrayOf(
                u,
                h * rnd(0.55f, 1.1f) * (1f - abs(u) * 0.7f),
                rnd(-0.4f, 0.4f),
                rnd(0f, 0.14f)
            )
        }
        fx.addEffect(0.7f) { canvas, k, _ ->
            val p = Path()
            val tips = ArrayList<FloatArray>()
            p.moveTo(x - w * 0.62f, y)
            for (s in ts) {
                val kk = clamp01((k - s[3]) / 0.8f)
                val up = sin(kk * PI.toFloat()) * s[1] * (1f - kk * 0.25f)
                val bx = x + s[0] * w
                val half = w / n * 0.62f
                val tx = bx + (s[0] * 1.6f + s[2]) * up * 0.5f
                tips.add(floatArrayOf(tx, y - up, up))
                p.quadTo(bx - half, y - up * 0.25f, tx, y - up)
                p.quadTo(bx + half * 0.5f, y - up * 0.3f, bx + half, y)
            }
            p.lineTo(x + w * 0.62f, y)
            p.close()
            canvas.drawPath(p, strokePaint(LINE, fx.dp(2.8f)))
            canvas.drawPath(p, fillPaint(SHADE))
            canvas.save()
            canvas.clipPath(p)
            for ((tx, ty, up) in tips) {
                if (up > 4f) {
                    canvas.drawOval(
                        tx - up * 0.2f - 1.4f, ty + up * 0.2f - up * 0.34f,
                        tx + up * 0.2f + 1.4f, ty + up * 0.2f + up * 0.34f,
                        fillPaint(FILL)
                    )
                }
            }
            canvas.restore()
        }
        // 水滴
        for (i in 0 until 7) {
            val a = -PI.toFloat() / 2 + rnd(-1.1f, 1.1f)
            val v = fx.dp(rnd(100f, 210f))
            val r0 = fx.dp(rnd(2f, 4f))
            val life = 0.7f * rnd(0.8f, 1.15f)
            fx.addEffect(life) { canvas, k, t ->
                val px = x + cos(a) * v * t
                val py = y - fx.dp(6f) + sin(a) * v * t + fx.dp(520f) * t * t
                val r = r0 * (1f - inn(clamp01((k - 0.6f) / 0.4f)))
                if (r <= 0.5f) return@addEffect
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate(0f)
                val d = Path()
                d.moveTo(-r * 2.6f, 0f)
                d.quadTo(-r * 0.6f, -r * 1.05f, r * 0.5f, -r * 0.9f)
                d.arcTo(r * 0.4f - r * 0.95f, -r * 0.95f, r * 0.4f + r * 0.95f, r * 0.95f, -80f, 160f, false)
                d.quadTo(-r * 0.6f, r * 1.05f, -r * 2.6f, 0f)
                twoTone(canvas, d, fx.dp(1f), r * 0.5f)
                canvas.restore()
            }
        }
    }

    /** 速度线：几笔向一个方向射出去、变细。 */
    fun lines(fx: FXView?, x: Float, y: Float, dir: Float = PI.toFloat(), lenDp: Float = 46f) {
        if (fx == null) return
        val len = fx.dp(lenDp)
        val spread = fx.dp(26f)
        for (i in 0 until 7) {
            val o = rnd(-spread, spread)
            val l = len * rnd(0.5f, 1.3f)
            val d = rnd(0f, 0.25f)
            val w = rnd(1f, 2.6f)
            fx.addEffect(0.3f) { canvas, k, _ ->
                val kk = clamp01((k - d) / (1f - d))
                val a = out(kk)
                val b = inn(kk)
                val px = x - sin(dir) * o
                val py = y + cos(dir) * o
                canvas.drawLine(
                    px, py,
                    px + cos(dir) * l * a, py + sin(dir) * l * a,
                    strokePaint(LINE, fx.dp(w) * (1f - b))
                )
            }
        }
    }

    /** 楔形：几片粉红楔子扇形散开（切页落点用）。 */
    fun ticks(fx: FXView?, x: Float, y: Float, n: Int = 5, dir: Float = -PI.toFloat() / 2) {
        if (fx == null) return
        for (i in 0 until n) {
            val a = dir + (i.toFloat() / (n - 1) - 0.5f) * 1.8f
            val len = fx.dp(rnd(21f, 36f))
            val w0 = fx.dp(rnd(5f, 9f))
            fx.addEffect(0.35f) { canvas, k, _ ->
                val l = len * out(clamp01(k / 0.4f))
                val w = w0 * (1f - k)
                if (l <= 1f || w <= 0.5f) return@addEffect
                val nx = -sin(a)
                val ny = cos(a)
                val p = Path()
                p.moveTo(x + cos(a) * l, y + sin(a) * l)
                p.lineTo(x + nx * w / 2, y + ny * w / 2)
                p.lineTo(x - nx * w / 2, y - ny * w / 2)
                p.close()
                canvas.drawPath(p, fillPaint(ACCENT))
            }
        }
    }

    /** 拟声字：描边大字，弹一下出现、缩小离场。dotted 时用虚线描边（唰那款）。 */
    fun sfx(
        fx: FXView?,
        word: String,
        x: Float,
        y: Float,
        sizeSp: Float = 26f,
        dotted: Boolean = false
    ) {
        if (fx == null) return
        val sizePx = sizeSp * fx.resources.displayMetrics.scaledDensity
        fx.addEffect(0.6f) { canvas, k, t ->
            val pop = back(clamp01(t / 0.2f))
            val die = 1f - inn(clamp01((k - 0.7f) / 0.3f))
            val scale = pop * die
            if (scale <= 0.02f) return@addEffect
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = sizePx
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            val baseline = y - (fm.ascent + fm.descent) / 2
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(-6f)
            canvas.scale(scale, scale)
            canvas.translate(-x, -y)
            if (dotted) {
                paint.style = Paint.Style.FILL
                paint.color = FILL
                canvas.drawText(word, x, baseline, paint)
                val path = Path()
                paint.getTextPath(word, 0, word.length, x, baseline, path)
                paint.style = Paint.Style.STROKE
                paint.color = INK
                paint.strokeWidth = fx.dp(2f)
                paint.pathEffect = DashPathEffect(floatArrayOf(fx.dp(6f), fx.dp(5f)), 0f)
                canvas.drawPath(path, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.color = FILL
                paint.strokeWidth = sizePx * 0.16f
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawText(word, x, baseline, paint)
                paint.style = Paint.Style.FILL
                paint.color = INK
                canvas.drawText(word, x, baseline, paint)
            }
            canvas.restore()
        }
    }

    // ── 以 View 为锚点的便捷入口（坐标换算到效果层） ──

    private fun toFxCoords(fx: FXView, view: View, fxLocal: Float, fyLocal: Float): FloatArray {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val floc = IntArray(2)
        fx.getLocationOnScreen(floc)
        return floatArrayOf(loc[0] + fxLocal - floc[0], loc[1] + fyLocal - floc[1])
    }

    private fun toFxCoords(fx: FXView, rawX: Float, rawY: Float): FloatArray {
        val floc = IntArray(2)
        fx.getLocationOnScreen(floc)
        return floatArrayOf(rawX - floc[0], rawY - floc[1])
    }

    fun burstAt(view: View, fx: Float, fy: Float, rDp: Float = 44f) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, fx, fy)
        burst(fxv, p[0], p[1], rDp)
    }

    fun burstAtCenter(view: View, rDp: Float = 44f) {
        burstAt(view, view.width / 2f, view.height / 2f, rDp)
    }

    fun puffAt(view: View, fx: Float, fy: Float, sizeDp: Float = 13f) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, fx, fy)
        puff(fxv, p[0], p[1], sizeDp)
    }

    fun shardsAt(view: View, fx: Float, fy: Float) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, fx, fy)
        shards(fxv, p[0], p[1])
    }

    fun slashAtCenter(view: View) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, view.width / 2f, view.height / 2f)
        slash(fxv, p[0], p[1])
    }

    fun splashAt(view: View, fx: Float, fy: Float) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, fx, fy)
        splash(fxv, p[0], p[1])
    }

    fun ticksAt(view: View, fx: Float, fy: Float) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, fx, fy)
        ticks(fxv, p[0], p[1])
    }

    fun sfxAt(
        view: View,
        word: String,
        fx: Float,
        fy: Float,
        sizeSp: Float = 26f,
        dotted: Boolean = false
    ) {
        val fxv = fxViewFor(view) ?: return
        val p = toFxCoords(fxv, view, fx, fy)
        sfx(fxv, word, p[0], p[1], sizeSp, dotted)
    }

    // ── 按压：挤压 + 回弹（弹簧），点按再补一击 ──
    //   返回 false，不抢手势：点击、长按、滑动都照常走。

    fun attachPress(view: View, onTap: ((FXView, Float, Float) -> Unit)? = null) {
        // 必须可点：不可点的 View 在 DOWN 上就返回 false，手势收不到 UP，onTap 永远不触发
        view.isClickable = true
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var pressed = false
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downTime = SystemClock.uptimeMillis()
                    pressed = true
                    squashIn(v)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressed && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                        pressed = false
                        release(v)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pressed) {
                        val dt = SystemClock.uptimeMillis() - downTime
                        val isTap = dt < 600 &&
                            abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop
                        release(v)
                        if (isTap && onTap != null) {
                            val fxv = fxViewFor(v)
                            if (fxv != null) {
                                val p = toFxCoords(fxv, ev.rawX, ev.rawY)
                                onTap(fxv, p[0], p[1])
                            }
                        }
                    }
                    pressed = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (pressed) release(v)
                    pressed = false
                }
            }
            false
        }
    }

    /** 按下：压扁（scaleY 0.86），保体积所以变宽（scaleX 1.08）。 */
    fun squashIn(view: View) {
        val fx = fxViewFor(view) ?: return
        val sx = Spring(view.scaleX)
        sx.to = 1.08f
        val sy = Spring(view.scaleY)
        sy.to = 0.86f
        fx.addSpring(view, sx, sy)
    }

    /** 松开：弹簧回弹，带一点过冲。 */
    fun release(view: View) {
        val fx = fxViewFor(view) ?: run {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        val sx = Spring(view.scaleX)
        sx.to = 1f
        val sy = Spring(view.scaleY)
        sy.to = 1f
        fx.addSpring(view, sx, sy)
    }
}

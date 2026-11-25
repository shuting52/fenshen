package top.niunaijun.blackboxa.view.sakuga

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import top.niunaijun.blackboxa.R

/**
 * 右上角 ⋮ 菜单的 GlassSelect 版。
 * GlassSelect / GlassPopup 是 liquid-glass 的 React + WebGL2 组件，原生 App 里跑不了；
 * 这里是按它的动作规则用本机重做的，动作照着文档一字不差：
 * 打开：列表从 ⋮ 按钮里整个胀出来，一根“脖子”在按钮和列表之间拉伸、绷断，选项一个接一个到齐；
 * 关闭：打开的动作倒着放一遍，列表缩回按钮里。
 * 点哪项的动作还是原来的 onOptionsItemSelected，功能一个没动；点按的爆裂 + 咚！留着，那是本软件自己的反馈。
 */
object SakugaMenu {

    fun show(anchor: View, menu: Menu, onItemSelected: (MenuItem) -> Boolean) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density

        // 容器：磨砂卡片 + 脖子 + 效果层（效果层画爆裂和拟声字，盖在最上，不接手势）
        val frame = FrameLayout(context).apply {
            isFocusableInTouchMode = true
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_select_card)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            minimumWidth = (200 * density).toInt()
        }
        val rows = ArrayList<TextView>()
        for (i in 0 until menu.size()) {
            val row = TextView(context).apply {
                text = menu.getItem(i).title
                setTextColor(ContextCompat.getColor(context, R.color.primary_text))
                textSize = 17f
                setPadding(
                    (20 * density).toInt(),
                    (13 * density).toInt(),
                    (20 * density).toInt(),
                    (13 * density).toInt()
                )
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            rows.add(row)
        }
        // “脖子”：连着按钮和列表的磨砂小胶囊，贴在卡片顶上、按钮正下方
        val neck = View(context).apply {
            setBackgroundResource(R.drawable.bg_glass_neck)
            scaleY = 0f
        }
        val fx = SakugaFX.FXView(context).apply { id = R.id.sakuga_fx_view }
        frame.addView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        frame.addView(
            neck,
            FrameLayout.LayoutParams(
                (18 * density).toInt(),
                (30 * density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = (15 * density).toInt()
            }
        )
        frame.addView(
            fx,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val popup = PopupWindow(
            frame,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = 8 * density

        // 开场先缩在按钮里，避免弹出的第一帧满尺寸闪一下
        frame.scaleX = 0.35f
        frame.scaleY = 0.35f
        frame.pivotX = 1000f
        frame.pivotY = 0f
        for (row in rows) {
            row.scaleX = 0.85f
            row.scaleY = 0.85f
        }

        var consumed = false
        var closing = false
        popup.setOnDismissListener { consumed = true }

        // 倒着收回去再关：选项倒着一个一个缩回去，列表再缩回按钮里，脖子一闪就断
        fun closeReverse(onDone: (() -> Unit)?) {
            if (closing || consumed) return
            closing = true
            for (i in rows.indices.reversed()) {
                val row = rows[i]
                row.animate().scaleX(0.6f).scaleY(0.6f).setDuration(120)
                    .setStartDelay(((rows.size - 1 - i) * 30).toLong()).start()
            }
            neck.animate().scaleY(0f).setDuration(120).start()
            frame.animate().scaleX(0.35f).scaleY(0.35f).setDuration(220)
                .setStartDelay(60).setInterpolator(AccelerateInterpolator()).start()
            frame.postDelayed({
                if (!consumed) {
                    consumed = true
                    popup.dismiss()
                }
                onDone?.invoke()
            }, 320)
        }

        // 点哪行：爆裂 + 咚！，再倒着收回去、走原来的菜单动作
        for (i in rows.indices) {
            val row = rows[i]
            val item = menu.getItem(i)
            SakugaFX.attachPress(row) { _, _, _ ->
                if (consumed || closing) return@attachPress
                SakugaFX.burstAtCenter(row)
                SakugaFX.sfxAt(row, SakugaFX.SFX_DON, row.width / 2f, -8f)
                closeReverse { onItemSelected(item) }
            }
        }

        // 点外面：不直接关，倒着收回去再关（ACTION_OUTSIDE 是系统递给弹窗的窗外触摸）
        popup.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && !consumed && !closing) {
                closeReverse(null)
                true
            } else {
                false
            }
        }
        // 按返回：同样倒着收回去再关（抢不到焦点就走系统默认直接关，不会坏事）
        frame.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !consumed && !closing) {
                closeReverse(null)
                true
            } else {
                false
            }
        }

        popup.showAsDropDown(anchor, 0, (4 * density).toInt(), Gravity.END)
        frame.requestFocus()
        // 开场：以 ⋮ 按钮为支点胀出来，脖子拉伸后绷断，选项一个接一个到齐
        frame.post {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val floc = IntArray(2)
            frame.getLocationOnScreen(floc)
            frame.pivotX = loc[0] + anchor.width / 2f - floc[0]
            frame.pivotY = 0f
            frame.animate().scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(OvershootInterpolator()).start()
            neck.animate().scaleY(1f).setDuration(140).withEndAction {
                neck.animate().scaleY(0f).setDuration(110).start()
            }.start()
            for (i in rows.indices) {
                val row = rows[i]
                row.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setStartDelay((120 + i * 60).toLong())
                    .setInterpolator(OvershootInterpolator()).start()
            }
        }
        // 开菜单也来一朵小冲击星（不带拟声字，干净）
        SakugaFX.burstAtCenter(anchor, 30f)
    }
}

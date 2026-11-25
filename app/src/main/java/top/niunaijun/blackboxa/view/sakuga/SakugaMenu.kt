package top.niunaijun.blackboxa.view.sakuga

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import top.niunaijun.blackboxa.R

/**
 * 右上角 ⋮ 菜单的 Sakuga 版：白纸卡片 + 逐项弹出 + 点按爆裂。
 * 从系统 Menu 里读标题，点哪项就调回原来的 onOptionsItemSelected，功能一个没动。
 * GlassActionButton 是 React 组件，在原生 App 里跑不了；
 * 这里是按它的思路（按压要有反馈）用本机做的同款：按下去扁一下，松手爆裂 + 咚！
 */
object SakugaMenu {

    fun show(anchor: View, menu: Menu, onItemSelected: (MenuItem) -> Boolean) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density

        // 容器：卡片 + 效果层（效果层画爆裂和拟声字，盖在最上，不接手势）
        val frame = FrameLayout(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sakuga_card)
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
        val fx = SakugaFX.FXView(context).apply { id = R.id.sakuga_fx_view }
        frame.addView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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

        var consumed = false
        popup.setOnDismissListener { consumed = true }

        // 点哪行：爆裂 + 咚！，180ms 后收窗、走原来的菜单动作
        for (i in rows.indices) {
            val row = rows[i]
            val item = menu.getItem(i)
            SakugaFX.attachPress(row) { _, _, _ ->
                if (consumed) return@attachPress
                SakugaFX.burstAtCenter(row)
                SakugaFX.sfxAt(row, SakugaFX.SFX_DON, row.width / 2f, -8f)
                row.postDelayed({
                    if (consumed) return@postDelayed
                    consumed = true
                    popup.dismiss()
                    onItemSelected(item)
                }, 180)
            }
        }

        popup.showAsDropDown(anchor, 0, (8 * density).toInt(), Gravity.END)
        // 逐项弹出：从 0.85 倍弹回，带回弹，每项隔 45ms
        for (i in rows.indices) {
            val row = rows[i]
            row.scaleX = 0.85f
            row.scaleY = 0.85f
            row.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setStartDelay((i * 45).toLong())
                .setInterpolator(OvershootInterpolator()).start()
        }
        // 开菜单也来一朵小冲击星（不带拟声字，干净）
        SakugaFX.burstAtCenter(anchor, 30f)
    }
}

// Sakuga 加载弹窗：暴风雨云上下浮动 + 轰隆隆 + 状态文字。
// 替代原来的猫咪加载（CatLoadingView），返回键照样拦住。
package top.niunaijun.blackboxa.view.sakuga

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import top.niunaijun.blackboxa.R

class SakugaLoadingDialog : DialogFragment() {

    var text: String = "LOADING..."
    private var textView: TextView? = null
    private var cloud: SakugaCloudView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnKeyListener { _, keyCode, _ ->
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        }
        return d
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.dialog_loading_sakuga, container, false)
        textView = v.findViewById(R.id.loading_text)
        textView?.text = text
        cloud = v.findViewById(R.id.loading_cloud)
        return v
    }

    override fun onStart() {
        super.onStart()
        cloud?.startBob()
        // 不挂 FX 效果层：MATCH_PARENT 的效果层会把 Dialog 窗口撑满屏高，卡片顶到顶部（2.3.30 同款教训）
    }

    override fun onStop() {
        cloud?.stopBob()
        super.onStop()
    }

    /** 外部设置提示文字（默认 LOADING...；创建分身时传“正在创建，请稍等”）。 */
    fun setLoadingText(t: String) {
        text = t
        textView?.text = t
    }
}

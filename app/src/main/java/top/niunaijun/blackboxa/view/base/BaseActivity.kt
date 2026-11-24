package top.niunaijun.blackboxa.view.base

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import top.niunaijun.blackboxa.R

/**
 *
 * @Description:BaseActivity
 * @Author: wukaicheng
 * @CreateDate: 2021/5/4 15:58
 */
open class BaseActivity : AppCompatActivity() {

    /** 自己已经铺了模糊壁纸的页面（主界面、列表页）不用再注入，免得叠两层 */
    protected open val hasOwnGlassBackground: Boolean = false

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        injectGlassBackground()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        injectGlassBackground()
    }

    /** 全局液态主题：每个页面根布局下第一层铺一块实时模糊，
     *  透出根布局自己的 boli 壁纸（@drawable/bg_boli），不用每个页面单独改。 */
    private fun injectGlassBackground() {
        if (hasOwnGlassBackground) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? ViewGroup ?: return
        if (root.findViewById<View>(R.id.injected_blur_view) != null) return
        val blurView = BlurView(this).apply {
            id = R.id.injected_blur_view
            layoutParams = when (root) {
                is ConstraintLayout -> ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                is FrameLayout -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                is LinearLayout -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                else -> ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        root.addView(blurView, 0)
        blurView.setupWith(root)
            .setBlurAlgorithm(RenderScriptBlur(this))
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)
            .setHasFixedTransformationMatrix(true)
            .setOverlayColor(0x66121212)
    }

    protected fun initToolbar(toolbar: Toolbar,title:Int, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        setSupportActionBar(toolbar)
        toolbar.setTitle(title)
        if (showBack) {
            supportActionBar?.let {
                it.setDisplayHomeAsUpEnabled(true)
                toolbar.setNavigationOnClickListener {
                    if (onBack != null) {
                        onBack()
                    }
                    finish()
                }
            }
        }
    }

    protected fun currentUserID():Int{
        return intent.getIntExtra("userID", 0)
    }
}

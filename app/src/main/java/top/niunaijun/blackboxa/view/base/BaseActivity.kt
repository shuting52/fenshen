package top.niunaijun.blackboxa.view.base

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import top.niunaijun.blackboxa.view.sakuga.SakugaFX

/**
 *
 * @Description:BaseActivity
 * @Author: wukaicheng
 * @CreateDate: 2021/5/4 15:58
 */
open class BaseActivity : AppCompatActivity() {

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        // Sakuga 效果层：挂在窗口最上层，只画效果，不接手势
        SakugaFX.attachToWindow(window)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        SakugaFX.attachToWindow(window)
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

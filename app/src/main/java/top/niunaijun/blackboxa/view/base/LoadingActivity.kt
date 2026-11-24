package top.niunaijun.blackboxa.view.base

import android.view.KeyEvent
import com.roger.catloadinglibrary.CatLoadingView
import top.niunaijun.blackboxa.R

/**
 *
 * @Description: loading activity
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:49
 */
abstract class LoadingActivity : BaseActivity() {

    private lateinit var loadingView: CatLoadingView


    /** text 只在创建分身时传“正在创建，请稍等”；其它操作（打开、卸载、清数据）不动，还是默认的 LOADING... */
    fun showLoading(text: String? = null) {
        if (!this::loadingView.isInitialized) {
            loadingView = CatLoadingView()
        }
        loadingView.setText(text ?: "LOADING...")

        if (!loadingView.isAdded) {
            loadingView.setBackgroundColor(R.color.primary)
            loadingView.show(supportFragmentManager, "")
            supportFragmentManager.executePendingTransactions()
            loadingView.setClickCancelAble(false)
            loadingView.dialog?.setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    return@setOnKeyListener true
                }
                false
            }
        }
    }


    fun hideLoading() {
        if (this::loadingView.isInitialized) {
            loadingView.dismiss()
        }
    }
}
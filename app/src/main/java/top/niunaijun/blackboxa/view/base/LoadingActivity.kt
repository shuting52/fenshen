package top.niunaijun.blackboxa.view.base

import top.niunaijun.blackboxa.view.sakuga.SakugaLoadingDialog

/**
 *
 * @Description: loading activity
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:49
 */
abstract class LoadingActivity : BaseActivity() {

    private var loadingDialog: SakugaLoadingDialog? = null


    /** text 只在创建分身时传“正在创建，请稍等”；其它操作（打开、卸载、清数据）不动，还是默认的 LOADING... */
    fun showLoading(text: String? = null) {
        val dialog = loadingDialog?.takeIf { it.isAdded } ?: SakugaLoadingDialog().also {
            loadingDialog = it
        }
        dialog.setLoadingText(text ?: "LOADING...")
        if (!dialog.isAdded) {
            dialog.show(supportFragmentManager, "sakuga_loading")
            supportFragmentManager.executePendingTransactions()
        }
    }


    fun hideLoading() {
        loadingDialog?.dismissAllowingStateLoss()
        loadingDialog = null
    }
}

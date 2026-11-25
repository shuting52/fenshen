package top.niunaijun.blackboxa.view.gms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Switch
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.databinding.ActivityGmsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.GmsCore
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.sakuga.SakugaFX

/**
 *
 * @Description: gms manager activity
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:06
 */
class GmsManagerActivity : LoadingActivity() {

    private lateinit var viewModel: GmsViewModel

    private lateinit var mAdapter: RVAdapter<GmsBean>

    private val viewBinding: ActivityGmsBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.gms_manager, true)
        initViewModel()

        initRecyclerView()

        // 手机上没有谷歌环境：提示这里装的是 microG 开源版
        if (!BlackBoxCore.get().isSupportGms) {
            viewBinding.gmsHint.visibility = View.VISIBLE
        }

        // 检查谷歌连接：分身和本软件走同一条网（同一个 VPN），这里通不通就是分身通不通
        SakugaFX.attachPress(viewBinding.gmsCheckNetwork) { _, _, _ -> checkGoogleNetwork() }
    }

    /** 测手机到谷歌通不通，8 秒超时，两个登录用的地址各测一次。 */
    private fun checkGoogleNetwork() {
        toast("正在检查…")
        Thread {
            val targets = listOf(
                "accounts.google.com" to "https://accounts.google.com/generate_204",
                "www.google.com" to "https://www.google.com/generate_204"
            )
            val lines = targets.map { (name, url) ->
                val start = System.currentTimeMillis()
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    "$name：能连上（${System.currentTimeMillis() - start}ms，$code）"
                } catch (e: Exception) {
                    "$name：连不上（${e.javaClass.simpleName}）"
                }
            }
            runOnUiThread {
                MaterialDialog(this).show {
                    title(R.string.gms_manager)
                    message(text = lines.joinToString("\n") + "\n\n连不上：换个 VPN 节点、或关掉 VPN 再试。能连上但登录还卡：登录页在慢慢加载，多等一会儿，或先在 microG 设置里登上谷歌账号，再回应用里点登录。")
                    positiveButton(R.string.done)
                }
            }
        }.start()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java]
        showLoading()

        viewModel.mInstalledLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
        }

        viewModel.mUpdateInstalledLiveData.observe(this) { result ->
            if (result == null) {
                return@observe
            }

            val items = mAdapter.getItems()
            for (index in items.indices) {
                val bean = items[index]
                if (bean.userID == result.userID) {
                    if (result.success) {
                        bean.isInstalledGms = !bean.isInstalledGms
                    }
                    mAdapter.replaceAt( index,bean)
                    break
                }
            }

            hideLoading()

            if (result.success) {
                if (result.isMicroG) {
                    // microG 装好了：问她要不要现在打开 microG 设置、登上谷歌账号
                    MaterialDialog(this).show {
                        title(R.string.gms_manager)
                        message(R.string.gms_open_microg)
                        positiveButton(R.string.gms_open_settings) {
                            BlackBoxCore.get().launchApk(GmsCore.GMS_PKG, result.userID)
                        }
                        negativeButton(R.string.cancel)
                    }
                } else {
                    toast(result.msg)
                }
            } else {
                MaterialDialog(this).show {
                    title(R.string.gms_manager)
                    message(text = result.msg)
                    positiveButton(R.string.done)
                }
            }
        }

        viewModel.getInstalledUser()
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<GmsBean>(this, GmsAdapter()).bind(viewBinding.recyclerView)
            .setItemClickListener { view, item, _ ->
                val checkbox = view.findViewById<Switch>(R.id.checkbox)
                if (item.isInstalledGms) {
                    uninstallGms(item.userID, checkbox)
                } else {
                    installGms(item.userID, checkbox)
                }
            }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

    }

    private fun installGms(userID: Int, checkbox: Switch){
        MaterialDialog(this).show {
            title(R.string.enable_gms)
            message(R.string.enable_gms_hint)
            positiveButton(R.string.done){
                showLoading()
                viewModel.installGms(userID)
            }
            negativeButton(R.string.cancel){
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private fun uninstallGms(userID: Int, checkbox: Switch){
        MaterialDialog(this).show {
            title(R.string.disable_gms)
            message(R.string.disable_gms_hint)
            positiveButton(R.string.done){
                showLoading()
                viewModel.uninstallGms(userID)
            }
            negativeButton(R.string.cancel){
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }


    companion object{
        fun start(context: Context){
            val intent = Intent(context,GmsManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}
package top.niunaijun.blackboxa.view.gms

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Switch
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
import top.niunaijun.blackboxa.app.CloneSpace
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.sakuga.SakugaFX
import java.net.HttpURLConnection
import java.net.URL

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

        // 打开 microG 设置：先在里面登上谷歌账号，应用里的 Google 登录才能直接选账号
        SakugaFX.attachPress(viewBinding.gmsOpenMicrogSettings) { _, _, _ -> openMicroGSettings() }
        // 测分身网络：VPN 有没有覆盖分身、谷歌登录页真能不能打开
        SakugaFX.attachPress(viewBinding.gmsCheckCloneNetwork) { _, _, _ -> checkCloneNetwork() }
    }

    /** 打开 microG 设置：找第一个装了谷歌服务的空间，在那里面登谷歌账号。
     *  先在 microG 里登上账号，应用里的 Google 登录才能直接选账号，不用再走网页。 */
    private fun openMicroGSettings() {
        // 列表是异步加载的，进页立刻点会读到空列表误报没装；直接按空间查安装状态
        val userId = CloneSpace.realUserIds().firstOrNull { BlackBoxCore.get().isInstallGms(it) }
        if (userId == null) {
            MaterialDialog(this).show {
                title(R.string.gms_manager)
                message(text = "还没有空间装谷歌服务：先打开一个空间的开关，把谷歌服务装进去。")
                positiveButton(R.string.done)
            }
            return
        }
        if (!BlackBoxCore.get().launchApk(GmsCore.GMS_PKG, userId)) {
            MaterialDialog(this).show {
                title(R.string.gms_manager)
                message(text = "打不开 microG 设置：进分身空间，在应用列表里点开 microG，再登谷歌账号。")
                positiveButton(R.string.done)
            }
        }
    }

    /** 测分身网络：分身和本软件是同一个 uid、走同一条路由，这里测的就是分身走的网。
     *  先看 VPN 有没有覆盖本软件（没覆盖就是 VPN 软件的名单问题），再看谷歌登录页真能不能打开。 */
    private fun checkCloneNetwork() {
        toast("正在测…")
        Thread {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val vpnOn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            var googleOk = false
            var googleMs = -1L
            try {
                val start = System.currentTimeMillis()
                val conn = URL("https://accounts.google.com/signin").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                googleOk = conn.responseCode in 200..399
                conn.disconnect()
                googleMs = System.currentTimeMillis() - start
            } catch (e: Exception) {
                googleOk = false
            }
            val lines = mutableListOf<String>()
            lines += if (vpnOn) "VPN：已覆盖淑婷分身，分身里的应用走的就是这个 VPN" else "VPN：没覆盖淑婷分身"
            lines += "谷歌登录页：" + if (googleOk) "能打开（${googleMs}ms）" else "打不开"
            lines += ""
            lines += when {
                !vpnOn -> "去 VPN 软件里把“淑婷分身”加进代理名单，或把 VPN 改成全局模式，再回来测一次。分身和本软件是同一个身份，VPN 覆盖了本软件就覆盖了分身。"
                googleOk && googleMs > 8000 -> "VPN 已覆盖，但节点太慢（${googleMs}ms 才打开登录页）：换个快节点再试。"
                googleOk -> "VPN 已覆盖、速度正常：分身里还打不开就是应用自己的问题，先在 microG 里登上谷歌账号再试。"
                else -> "VPN 已覆盖但谷歌打不开：换个 VPN 节点再试。"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                MaterialDialog(this).show {
                    title(R.string.gms_manager)
                    message(text = lines.joinToString("\n"))
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
                // 按数据复位，不取反：点行进来的开关本来就没动过，取反会显示错状态
                checkbox.isChecked = mAdapter.getItems().firstOrNull { it.userID == userID }?.isInstalledGms ?: checkbox.isChecked
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
                // 按数据复位，不取反：点行进来的开关本来就没动过，取反会显示错状态
                checkbox.isChecked = mAdapter.getItems().firstOrNull { it.userID == userID }?.isInstalledGms ?: checkbox.isChecked
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
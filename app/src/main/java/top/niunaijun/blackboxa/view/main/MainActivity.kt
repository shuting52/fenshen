package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.edit
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.BXposedManager
import top.niunaijun.blackboxa.BuildConfig
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.app.CloneSpace
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.Resolution
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.apps.AppsFragment
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.list.ListViewModel
import eightbitlab.com.blurview.RenderScriptBlur
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity


class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        previewInstalledAppList()
        setupBlur()
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
        initViewPager()
        initFab()
        initToolbarSubTitle()
        checkAppUpdate()
    }

    /** 启动时查一次 fenshen 仓库的最新 Release，有新版就弹更新提醒；没网或查不到就静默跳过 */
    private fun checkAppUpdate() {
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/shuting52/fenshen/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@Thread
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name", "").trimStart('v')
                val htmlUrl = json.optString("html_url", "")
                val body = json.optString("body", "")
                if (tag.isEmpty() || htmlUrl.isEmpty()) return@Thread
                if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return@Thread
                runOnUiThread {
                    MaterialDialog(this).show {
                        title(text = "发现新版本 $tag")
                        message(text = body.ifEmpty { "淑婷分身有新版本，去下载更新。" })
                        positiveButton(R.string.update_download) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                        }
                        negativeButton(R.string.cancel)
                    }
                }
            } catch (e: Exception) {
                // 没网 / 接口不通：不打扰
            }
        }.start()
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(l.size, c.size)
        for (i in 0 until size) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private fun previewInstalledAppList() {
        val viewModel = ViewModelProvider(this, InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }

    private fun setupBlur() {
        viewBinding.blurView.setupWith(viewBinding.root)
            .setBlurAlgorithm(RenderScriptBlur(this))
            .setBlurRadius(16f)
            .setBlurAutoUpdate(true)
            .setHasFixedTransformationMatrix(true)
        viewBinding.blurView.setOverlayColor(Color.parseColor("#66121212"))
    }

    private fun initToolbarSubTitle() {
        updateUserRemark(0)
        //hack code
        viewBinding.toolbarLayout.toolbar.getChildAt(1).setOnClickListener {
            MaterialDialog(this).show {
                title(res = R.string.userRemark)
                input(
                    hintRes = R.string.userRemark,
                    prefill = viewBinding.toolbarLayout.toolbar.subtitle
                ) { _, input ->
                    AppManager.mRemarkSharedPreferences.edit {
                        putString("Remark$currentUser", input.toString())
                        viewBinding.toolbarLayout.toolbar.subtitle = input
                    }
                }
                positiveButton(res = R.string.done)
                negativeButton(res = R.string.cancel)
            }
        }
    }

    private fun initViewPager() {

        val userList = CloneSpace.realUserIds()
        userList.forEach {
            fragmentList.add(AppsFragment.newInstance(it))
        }

        currentUser = userList.firstOrNull() ?: 0
        fragmentList.add(AppsFragment.newInstance(userList.size))

        mViewPagerAdapter = ViewPagerAdapter(this)
        mViewPagerAdapter.replaceData(fragmentList)
        viewBinding.viewPager.adapter = mViewPagerAdapter
        viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
        viewBinding.viewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentUser = fragmentList[position].userID
                updateUserRemark(currentUser)
                showFloatButton(true)
            }
        })

    }

    private fun initFab() {
        viewBinding.fab.setOnClickListener {
            val userId = viewBinding.viewPager.currentItem
            val intent = Intent(this, ListActivity::class.java)
            intent.putExtra("userID", userId)
            apkPathResult.launch(intent)
        }
    }

    fun showFloatButton(show: Boolean) {
        val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
        val time = 200L
        if (show) {
            viewBinding.fab.animate().translationY(0f).alpha(1f).setDuration(time)
                .start()
        } else {
            viewBinding.fab.animate().translationY(tranY).alpha(0f).setDuration(time)
                .start()
        }
    }

    fun scanUser() {
        val userList = CloneSpace.realUserIds()

        if (fragmentList.size == userList.size) {
            fragmentList.add(AppsFragment.newInstance(fragmentList.size))
        } else if (fragmentList.size > userList.size + 1) {
            fragmentList.removeLast()
        }

        mViewPagerAdapter.notifyDataSetChanged()

    }

    private fun updateUserRemark(userId: Int) {
        viewBinding.toolbarLayout.toolbar.subtitle = CloneSpace.getRemark(userId)
    }

    private val apkPathResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                it.data?.let { data ->
                    val userId = data.getIntExtra("userID", 0)
                    val source = data.getStringExtra("source")
                    val count = data.getIntExtra("count", 1)
                    if (source != null) {
                        fragmentList[userId].installApk(source, count)
                    }
                }

            }
        }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {

            R.id.main_setting -> {
                SettingActivity.start(this)
            }

            R.id.fake_location -> {
//                toast("Still Developing")
                val intent = Intent(this, FakeManagerActivity::class.java)
                intent.putExtra("userID", 0)
                startActivity(intent)
            }

            R.id.main_refresh_process -> {
                BXposedManager.get().killAllProcesses()
                toast(R.string.refresh_process_done)
            }
        }

        return true
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

}

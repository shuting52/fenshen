package top.niunaijun.blackboxa.view.main

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.core.content.FileProvider
import java.io.File
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
import top.niunaijun.blackboxa.view.glass.GlassWallpaper
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity


class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0

    private var updateDownloadId: Long = -1L
    private var updateApkFileName: String? = null

    /** 上次查更新的时间（毫秒），回到 App 时超过 10 分钟就再查一次 */
    private var lastUpdateCheckTime: Long = 0L

    /** 下载完成就直接调安装器，不跳浏览器 */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != updateDownloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    updateApkFileName?.let { installDownloadedApk(it) }
                } else {
                    toast(R.string.update_download_failed)
                }
            }
            cursor.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        previewInstalledAppList()
        setupBlur()
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
        initViewPager()
        initFab()
        initToolbarSubTitle()
        // 液态玻璃：后台先把模糊壁纸准备好，卡片画出来就是透镜
        Thread { GlassWallpaper.prepare(this) }.start()
        cleanOldUpdateApks()
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        checkAppUpdate()
    }

    /** 回到 App 也查一次：之前只在冷启动时查，App 一直挂在后台就永远查不到新版 */
    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckTime > 10 * 60 * 1000L) {
            checkAppUpdate()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // 没注册过就不用管
        }
        super.onDestroy()
    }

    /** 装完新版下次打开，删掉上次下载残留的安装包 */
    private fun cleanOldUpdateApks() {
        Thread {
            val prefs = getSharedPreferences("fenshen", Context.MODE_PRIVATE)
            val lastCode = prefs.getInt("installed_version_code", -1)
            if (lastCode != BuildConfig.VERSION_CODE) {
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?.listFiles { f ->
                        f.name.startsWith("Shuting-Fenshen-") && f.name.endsWith(".apk")
                    }?.forEach { it.delete() }
                prefs.edit { putInt("installed_version_code", BuildConfig.VERSION_CODE) }
            }
        }.start()
    }

    /** 查一次 fenshen 仓库的最新 Release，有新版就弹更新提醒。
     *  点“立即更新”直接在 App 里下载安装包，下载完自动调起安装，不跳浏览器。
     *  只有 Release 里找不到安装包时才回退到打开 Release 页。
     *  自动查时没网或查不到就静默跳过；手动点“检查更新”时把结果说出来。 */
    private fun checkAppUpdate(manual: Boolean = false) {
        lastUpdateCheckTime = System.currentTimeMillis()
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/shuting52/fenshen/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) {
                    if (manual) runOnUiThread { toast(R.string.update_check_failed) }
                    return@Thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name", "").trimStart('v')
                val body = json.optString("body", "")
                if (tag.isEmpty()) {
                    if (manual) runOnUiThread { toast(R.string.update_check_failed) }
                    return@Thread
                }
                if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                    if (manual) runOnUiThread { toast(R.string.update_latest) }
                    return@Thread
                }
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val name = assets.getJSONObject(i).optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = assets.getJSONObject(i)
                                .optString("browser_download_url", "")
                            break
                        }
                    }
                }
                val htmlUrl = json.optString("html_url", "")
                runOnUiThread {
                    MaterialDialog(this).show {
                        title(text = "发现新版本 $tag")
                        message(text = body.ifEmpty { "淑婷分身有新版本。" })
                        positiveButton(R.string.update_now) {
                            if (apkUrl.isNullOrEmpty()) {
                                if (htmlUrl.isNotEmpty()) {
                                    startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))
                                    )
                                }
                            } else {
                                startUpdateDownload(tag, apkUrl)
                            }
                        }
                        negativeButton(R.string.cancel)
                    }
                }
            } catch (e: Exception) {
                // 没网 / 接口不通：自动查时不打扰，手动查时说出来
                if (manual) runOnUiThread { toast(R.string.update_check_failed) }
            }
        }.start()
    }

    /** 系统下载器下载新版，下载完自动安装 */
    private fun startUpdateDownload(tag: String, apkUrl: String) {
        // Android 8+：没开“允许安装未知应用”时，先去开权限，回来下次启动再弹更新
        if (!packageManager.canRequestPackageInstalls()) {
            toast(R.string.update_need_install_permission)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        val fileName = "Shuting-Fenshen-$tag.apk"
        updateApkFileName = fileName
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("淑婷分身 $tag")
            .setDescription(getString(R.string.update_downloading))
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                this, Environment.DIRECTORY_DOWNLOADS, fileName
            )
            .setMimeType("application/vnd.android.package-archive")
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        updateDownloadId = dm.enqueue(request)
        toast(R.string.update_downloading)
    }

    private fun installDownloadedApk(fileName: String) {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            toast(R.string.update_download_failed)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
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
            .setBlurRadius(8f)
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

            R.id.main_check_update -> {
                checkAppUpdate(manual = true)
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

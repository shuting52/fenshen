package top.niunaijun.blackboxa.view.main

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
import top.niunaijun.blackboxa.app.CrashLog
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

    override val hasOwnGlassBackground: Boolean = true

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0

    /** 上次查更新的时间（毫秒），回到 App 时超过 10 分钟就再查一次 */
    private var lastUpdateCheckTime: Long = 0L

    /** 更新弹窗：打开时不为空；下载进行中标志，关弹窗就停下载 */
    private var updateDialog: Dialog? = null
    private var updatePolling: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.bread("MainActivity onCreate")
        setContentView(viewBinding.root)
        CrashLog.bread("MainActivity setContentView ok")
        previewInstalledAppList()
        setupBlur()
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
        // 顶栏只留空间名副标题，“淑婷分身”标题字不要了。
        // 注意：标题不能设成空字符串——顶栏只在标题非空时才建标题 View，
        // 标题 View 没了，副标题就从第 2 位挪到第 1 位，initToolbarSubTitle
        // 里 getChildAt(1) 拿到空就崩了（2.3.12/2.3.13 启动闪退的根因）。
        // 设成一个空格：看着没字，View 还在，位置不错位。
        viewBinding.toolbarLayout.toolbar.title = " "
        // 连 Activity 自身的标题也清成空格：不然系统启动后会按应用名把它写回顶栏
        title = " "
        initViewPager()
        CrashLog.bread("MainActivity initViewPager ok")
        initFab()
        initToolbarSubTitle()
        // 液态玻璃：后台先把模糊壁纸准备好，卡片画出来就是透镜
        Thread { GlassWallpaper.prepare(this) }.start()
        cleanOldUpdateApks()
        checkAppUpdate()
        CrashLog.bread("MainActivity onCreate ok")
    }

    /** 回到 App 也查一次：之前只在冷启动时查，App 一直挂在后台就永远查不到新版 */
    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckTime > 10 * 60 * 1000L) {
            checkAppUpdate()
        }
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
                    showUpdateDialog(tag, body, apkUrl, htmlUrl)
                }
            } catch (e: Exception) {
                // 没网 / 接口不通：自动查时不打扰，手动查时说出来
                if (manual) runOnUiThread { toast(R.string.update_check_failed) }
            }
        }.start()
    }

    /** 自己下载新版，不走系统下载器：系统下载器下完会在状态栏弹“下载已完成”通知，
     *  自己下就没有任何通知，进度直接写进弹窗，下载完自动调安装器。
     *  第一次安装时系统安装器自己会问一次（安卓的规定），允许一次之后就再也不会出现。
     *  关掉弹窗就取消下载，半截文件删掉。 */
    private fun startUpdateDownload(
        tag: String, apkUrl: String,
        progressBar: ProgressBar, progressText: TextView,
        progressBox: LinearLayout, buttonBox: LinearLayout
    ) {
        val fileName = "Shuting-Fenshen-$tag.apk"
        val outFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        updatePolling = true
        Thread {
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (updatePolling) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    progressBar.progress = percent
                                    progressText.text =
                                        getString(R.string.update_downloading_percent, percent)
                                }
                            }
                        }
                    }
                }
                // 弹窗关了就是取消下载，半截文件删掉
                if (!updatePolling) {
                    outFile.delete()
                    return@Thread
                }
                if (total > 0 && done < total) throw IOException("incomplete")
                runOnUiThread {
                    updatePolling = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateDialog?.dismiss()
                    installDownloadedApk(fileName)
                }
            } catch (e: Exception) {
                CrashLog.bread("update download failed: ${e.message}")
                outFile.delete()
                runOnUiThread {
                    updatePolling = false
                    if (isFinishing || isDestroyed || updateDialog?.isShowing != true) return@runOnUiThread
                    progressText.setText(R.string.update_download_failed)
                    buttonBox.visibility = View.VISIBLE
                    progressBox.visibility = View.GONE
                }
            }
        }.start()
    }

    /** 更新弹窗：猫咪圆环转圈、猫脸上下浮动、标题弹出，内容直接显示全文。
     *  点“立即更新”直接开始下载，弹窗里显示下载进度，下载完自动调安装器。 */
    private fun showUpdateDialog(tag: String, body: String, apkUrl: String?, htmlUrl: String) {
        updateDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update)
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val lp = it.attributes
            lp?.windowAnimations = R.style.UpdateDialogAnim
            it.attributes = lp
            it.setDimAmount(0.2f)
            val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            it.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)
        val ring = dialog.findViewById<ImageView>(R.id.update_cat_ring)
        val face = dialog.findViewById<ImageView>(R.id.update_cat_face)
        val titleView = dialog.findViewById<TextView>(R.id.update_title)
        val messageView = dialog.findViewById<TextView>(R.id.update_message)
        val progressBox = dialog.findViewById<LinearLayout>(R.id.update_progress_box)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.update_progress_bar)
        val progressText = dialog.findViewById<TextView>(R.id.update_progress_text)
        val buttonBox = dialog.findViewById<LinearLayout>(R.id.update_button_box)
        val cancelBtn = dialog.findViewById<TextView>(R.id.update_cancel)
        val nowBtn = dialog.findViewById<TextView>(R.id.update_now)

        titleView.text = "发现新版本 $tag"
        // 标题弹出
        titleView.scaleX = 0.8f
        titleView.scaleY = 0.8f
        titleView.animate().scaleX(1f).scaleY(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator()).start()
        // 猫咪圆环一直转，猫脸上下浮动
        val ringAnim = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
            duration = 2500L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        val faceAnim = ObjectAnimator.ofFloat(face, "translationY", 0f, -10f, 0f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        // 更新内容直接显示全文，不打字机
        messageView.text = body.ifEmpty { "淑婷分身有新版本。" }
        dialog.setOnDismissListener {
            updatePolling = false
            ringAnim.cancel()
            faceAnim.cancel()
            updateDialog = null
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        nowBtn.setOnClickListener {
            if (apkUrl.isNullOrEmpty()) {
                if (htmlUrl.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                }
                dialog.dismiss()
            } else {
                buttonBox.visibility = View.GONE
                progressBox.visibility = View.VISIBLE
                progressBar.progress = 0
                progressText.text = getString(R.string.update_downloading_percent, 0)
                startUpdateDownload(tag, apkUrl, progressBar, progressText, progressBox, buttonBox)
            }
        }
        updateDialog = dialog
        dialog.show()
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
        viewBinding.blurView.setOverlayColor(Color.parseColor("#80FFFFFF"))
    }

    private fun initToolbarSubTitle() {
        updateUserRemark(0)
        //hack code：副标题 View 在顶栏第 2 位；取不到也不许崩启动
        viewBinding.toolbarLayout.toolbar.getChildAt(1)?.setOnClickListener {
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

        // 延后通知：FragmentManager 正执行事务时直接 notify 会闪退，推到下一轮主线程再通知
        viewBinding.viewPager.post { mViewPagerAdapter.notifyDataSetChanged() }

    }

    private fun updateUserRemark(userId: Int) {
        viewBinding.toolbarLayout.toolbar.subtitle = CloneSpace.getRemark(userId)
    }

    /** 一键删除当前空间：它名下的隐藏分身用户一起删，备注和映射也清掉；只剩一个空间时不让删 */
    private fun deleteCurrentSpace() {
        val userList = CloneSpace.realUserIds()
        if (userList.size <= 1) {
            toast(R.string.space_delete_last)
            return
        }
        val spaceId = currentUser
        val remark = CloneSpace.getRemark(spaceId)
        MaterialDialog(this).show {
            title(text = getString(R.string.delete_space_confirm_title, remark))
            message(text = getString(R.string.delete_space_hint))
            positiveButton(R.string.done) {
                showLoading()
                Thread {
                    CloneSpace.spaceMembers(spaceId).forEach { id ->
                        if (id != spaceId) BlackBoxCore.get().deleteUser(id)
                    }
                    BlackBoxCore.get().deleteUser(spaceId)
                    CloneSpace.removeSpace(spaceId)
                    runOnUiThread {
                        hideLoading()
                        rebuildViewPager()
                        toast(getString(R.string.space_deleted, remark))
                    }
                }.start()
            }
            negativeButton(R.string.cancel)
        }
    }

    /** 删完空间后整页重建：新造一套 Fragment + 新造一个 Adapter，位置和内容不会错位 */
    private fun rebuildViewPager() {
        val userList = CloneSpace.realUserIds()
        fragmentList.clear()
        userList.forEach { fragmentList.add(AppsFragment.newInstance(it)) }
        fragmentList.add(AppsFragment.newInstance(userList.size))
        currentUser = userList.firstOrNull() ?: 0
        mViewPagerAdapter = ViewPagerAdapter(this)
        mViewPagerAdapter.replaceData(fragmentList)
        viewBinding.viewPager.adapter = mViewPagerAdapter
        viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
        viewBinding.viewPager.setCurrentItem(0, false)
        updateUserRemark(currentUser)
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

            R.id.main_delete_space -> {
                deleteCurrentSpace()
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

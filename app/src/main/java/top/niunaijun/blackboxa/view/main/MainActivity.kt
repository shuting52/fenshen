package top.niunaijun.blackboxa.view.main

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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

    private var updateDownloadId: Long = -1L
    private var updateApkFileName: String? = null

    /** 上次查更新的时间（毫秒），回到 App 时超过 10 分钟就再查一次 */
    private var lastUpdateCheckTime: Long = 0L

    /** 卡通更新弹窗：打开时不为空；下载进度轮询靠它停 */
    private var updateDialog: Dialog? = null
    private var updatePolling: Boolean = false
    private val updateHandler = Handler(Looper.getMainLooper())
    private var typewriterRunnable: Runnable? = null

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
                    updatePolling = false
                    updateDialog?.dismiss()
                    updateApkFileName?.let { installDownloadedApk(it) }
                } else {
                    updatePolling = false
                    updateDialog?.let { d ->
                        d.findViewById<TextView>(R.id.update_progress_text)
                            ?.setText(R.string.update_download_failed)
                        d.findViewById<LinearLayout>(R.id.update_button_box)
                            ?.visibility = View.VISIBLE
                        d.findViewById<LinearLayout>(R.id.update_progress_box)
                            ?.visibility = View.GONE
                    } ?: toast(R.string.update_download_failed)
                }
            }
            cursor.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showCrashLogIfAny()
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
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        checkAppUpdate()
        CrashLog.bread("MainActivity onCreate ok")
    }

    /** 诊断版：上次闪退留下的记录，先弹出来，点复制发我 */
    private fun showCrashLogIfAny() {
        val f = CrashLog.file() ?: return
        if (!f.exists()) return
        val text = try { f.readText() } catch (e: Exception) { return }
        if (!text.contains("CRASH")) return
        val shown = if (text.length > 4000) text.takeLast(4000) else text
        MaterialDialog(this).show {
            title(R.string.crash_last_title)
            message(text = shown)
            positiveButton(R.string.copy_text) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                toast(R.string.copy_done)
            }
            negativeButton(R.string.crash_clear) { f.delete() }
            neutralButton(R.string.cancel)
        }
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
                    showUpdateDialog(tag, body, apkUrl, htmlUrl)
                }
            } catch (e: Exception) {
                // 没网 / 接口不通：自动查时不打扰，手动查时说出来
                if (manual) runOnUiThread { toast(R.string.update_check_failed) }
            }
        }.start()
    }

    /** 系统下载器下载新版：点“立即更新”就直接下，不再先跳“安装未知应用”设置页；
     *  下载完自动调安装器。第一次安装时系统安装器自己会问一次（安卓的规定），
     *  允许一次之后就再也不会出现。 */
    private fun startUpdateDownload(tag: String, apkUrl: String) {
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
    }

    /** 卡通动态更新弹窗：猫咪圆环转圈、猫脸上下浮动、标题弹出、更新内容打字机逐字打出。
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
            it.setDimAmount(0.5f)
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
        // 更新内容打字机逐字打出
        val fullText = body.ifEmpty { "淑婷分身有新版本。" }
        messageView.text = ""
        var index = 0
        typewriterRunnable = object : Runnable {
            override fun run() {
                if (index <= fullText.length && updateDialog != null) {
                    messageView.text = fullText.substring(0, index)
                    index++
                    updateHandler.postDelayed(this, 25)
                }
            }
        }
        updateHandler.post(typewriterRunnable!!)
        dialog.setOnDismissListener {
            updatePolling = false
            ringAnim.cancel()
            faceAnim.cancel()
            typewriterRunnable?.let { updateHandler.removeCallbacks(it) }
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
                startUpdateDownload(tag, apkUrl)
                startDownloadProgressPolling(progressBar, progressText)
            }
        }
        updateDialog = dialog
        dialog.show()
    }

    /** 下载进度轮询：每 300ms 查一次下载器，把进度写进弹窗 */
    private fun startDownloadProgressPolling(progressBar: ProgressBar, progressText: TextView) {
        updatePolling = true
        Thread {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (updatePolling) {
                try {
                    val cursor = dm.query(DownloadManager.Query().setFilterById(updateDownloadId))
                    if (cursor.moveToFirst()) {
                        val total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        val done = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        runOnUiThread {
                            progressBar.progress = percent
                            progressText.text =
                                getString(R.string.update_downloading_percent, percent)
                        }
                        if (status != DownloadManager.STATUS_PENDING &&
                            status != DownloadManager.STATUS_RUNNING
                        ) break
                    }
                    cursor.close()
                } catch (e: Exception) {
                    break
                }
                Thread.sleep(300)
            }
        }.start()
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
        viewBinding.blurView.setOverlayColor(Color.parseColor("#66FFFFFF"))
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

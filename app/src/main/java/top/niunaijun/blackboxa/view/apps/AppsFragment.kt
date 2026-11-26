package top.niunaijun.blackboxa.view.apps

import android.graphics.Point
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.CloneSpace
import top.niunaijun.blackboxa.app.CrashLog
import top.niunaijun.blackboxa.app.MuseUsage
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.FragmentAppsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.main.MainActivity
import top.niunaijun.blackboxa.view.sakuga.SakugaFX
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs


/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 22:21
 */
class AppsFragment : Fragment() {

    var userID: Int = 0

    private lateinit var viewModel: AppsViewModel

    private lateinit var mAdapter: RVAdapter<AppInfo>

    private lateinit var appsAdapter: AppsAdapter

    private val viewBinding: FragmentAppsBinding by inflate()

    private var popupMenu: PopupMenu? = null

    /** Muse 额度刷新后重绑卡片，让分身下面的剩余额度跟着变。 */
    private val quotaListener: () -> Unit = {
        if (isAdded && this::mAdapter.isInitialized) mAdapter.notifyDataSetChanged()
    }

    private var hotfixPackage: String? = null

    private var hotfixUserId: Int = 0

    private val patchPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val pkg = hotfixPackage
            if (uri != null && pkg != null) {
                // 结果在 onStart 事务分发期间回调，这里不能同步操作 FragmentManager（showLoading），
                // 拷贝完成后由 resultLiveData 弹 toast
                viewModel.saveHotfixPatch(hotfixUserId, pkg, uri)
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel =
            ViewModelProvider(this, InjectionUtil.getAppsFactory()).get(AppsViewModel::class.java)
        userID = requireArguments().getInt("userID", 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        CrashLog.bread("AppsFragment onCreateView userId=$userID")
        viewBinding.stateView.showEmpty()

        appsAdapter = AppsAdapter()
        appsAdapter.spaceId = userID
        appsAdapter.onSelectionChanged = { updateBatchBar() }
        mAdapter =
            RVAdapter<AppInfo>(requireContext(), appsAdapter).bind(viewBinding.recyclerView)

        viewBinding.recyclerView.adapter = mAdapter
        viewBinding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 4)

        val touchCallBack = AppsTouchCallBack { from, to ->
            onItemMove(from, to)
            viewModel.updateSortLiveData.postValue(true)
        }

        val itemTouchHelper = ItemTouchHelper(touchCallBack)
        itemTouchHelper.attachToRecyclerView(viewBinding.recyclerView)

        mAdapter.setItemClickListener { _, data, position ->
            if (appsAdapter.selectMode) {
                toggleSelection(data, position)
            } else {
                showLoading()
                viewModel.launchApk(data.packageName, data.userId)
            }
        }

        viewBinding.batchDelete.setOnClickListener { deleteSelected() }
        viewBinding.batchCancel.setOnClickListener { exitSelectionMode() }


        interceptTouch()
        setOnLongClick()
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initData()
    }

    override fun onStart() {
        super.onStart()
        CrashLog.bread("AppsFragment onStart userId=$userID")
        viewModel.getInstalledApps(userID)
        MuseUsage.addListener(quotaListener)
        MuseUsage.fetch(requireContext())
    }

    /**
     * 拖拽优化
     */
    private fun interceptTouch() {
        val point = Point()
        viewBinding.recyclerView.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_UP -> {
                    if (!isMove(point, e)) {
                        popupMenu?.show()
                    }
                    popupMenu = null
                    point.set(0, 0)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (point.x == 0 && point.y == 0) {
                        point.x = e.rawX.toInt()
                        point.y = e.rawY.toInt()
                    }
                    isDownAndUp(point, e)

                    if (isMove(point, e)) {
                        popupMenu?.dismiss()
                    }
                }
            }
            return@setOnTouchListener false
        }
    }

    private fun isMove(point: Point, e: MotionEvent): Boolean {
        val max = 40

        val x = point.x
        val y = point.y

        val xU = abs(x - e.rawX)
        val yU = abs(y - e.rawY)
        return xU > max || yU > max
    }

    private fun isDownAndUp(point: Point, e: MotionEvent) {
        if (this::appsAdapter.isInitialized && appsAdapter.selectMode) return
        val min = 10
        val y = point.y
        val yU = y - e.rawY

        if (abs(yU) > min) {
            (requireActivity() as MainActivity).showFloatButton(yU < 0)
        }
    }

    private fun onItemMove(fromPosition:Int, toPosition:Int){
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(mAdapter.getItems(), i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(mAdapter.getItems(), i, i - 1)
            }
        }
        mAdapter.notifyItemMoved(fromPosition, toPosition)
    }

    /** 批量删除：长按一个分身进多选，再点别的分身勾上，底部一键删 */
    private fun enterSelectionMode(first: AppInfo, anchor: View? = null) {
        appsAdapter.selectMode = true
        appsAdapter.selected.add(appsAdapter.selectionKey(first))
        mAdapter.notifyDataSetChanged()
        viewBinding.batchBar.visibility = View.VISIBLE
        (requireActivity() as MainActivity).showFloatButton(false)
        updateBatchBar()
        // 进多选：一道斩击 + 唰
        if (anchor != null) {
            SakugaFX.slashAtCenter(anchor)
            SakugaFX.sfxAt(
                anchor, SakugaFX.SFX_ZUZA,
                anchor.width / 2f, anchor.height / 2f - 24f,
                sizeSp = 18f, dotted = true
            )
        }
    }

    private fun exitSelectionMode() {
        appsAdapter.selectMode = false
        appsAdapter.selected.clear()
        mAdapter.notifyDataSetChanged()
        viewBinding.batchBar.visibility = View.GONE
        (requireActivity() as MainActivity).showFloatButton(true)
    }

    private fun toggleSelection(data: AppInfo, position: Int) {
        val key = appsAdapter.selectionKey(data)
        val nowSelected = if (!appsAdapter.selected.remove(key)) {
            appsAdapter.selected.add(key)
            true
        } else {
            false
        }
        mAdapter.notifyItemChanged(position)
        updateBatchBar()
        // 勾上：水晶碎片飞起来 + 唰
        if (nowSelected) {
            val holder = viewBinding.recyclerView.findViewHolderForAdapterPosition(position)
            val itemView = holder?.itemView
            if (itemView != null) {
                SakugaFX.shardsAt(itemView, itemView.width / 2f, itemView.height / 2f)
                SakugaFX.sfxAt(
                    itemView, SakugaFX.SFX_ZUZA,
                    itemView.width / 2f, itemView.height / 2f - 24f,
                    sizeSp = 18f, dotted = true
                )
            }
        }
    }

    private fun updateBatchBar() {
        viewBinding.batchDelete.text =
            getString(R.string.delete_selected, appsAdapter.selected.size.toString())
    }

    private fun deleteSelected() {
        val items = mAdapter.getItems()
            .filter { appsAdapter.selected.contains(appsAdapter.selectionKey(it)) }
        if (items.isEmpty()) {
            exitSelectionMode()
            return
        }
        MaterialDialog(requireContext()).show {
            title(R.string.batch_delete_title)
            message(text = getString(R.string.batch_delete_hint, items.size.toString()))
            positiveButton(R.string.done) {
                exitSelectionMode()
                showLoading(getString(R.string.batch_deleting))
                viewModel.unInstallBatch(items, userID)
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun setOnLongClick() {
        mAdapter.setItemLongClickListener { view, data, position ->
            if (appsAdapter.selectMode) {
                toggleSelection(data, position)
                return@setItemLongClickListener
            }
            popupMenu = PopupMenu(requireContext(),view).also {
                it.inflate(R.menu.app_menu)
                it.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.app_remove -> {
                            if (data.isXpModule) {
                                toast(R.string.uninstall_module_toast)
                            } else {
                                unInstallApk(data)
                            }
                        }

                        R.id.app_clear -> {
                            clearApk(data)
                        }

                        R.id.app_stop -> {
                            stopApk(data)
                        }

                        R.id.app_hotfix -> {
                            showHotfixDialog(data)
                        }

                        R.id.app_shortcut -> {
                            ShortcutUtil.createShortcut(requireContext(), data.userId, data)
                        }

                        R.id.app_rename -> {
                            showRenameDialog(data)
                        }

                        R.id.app_batch_delete -> {
                            enterSelectionMode(data, view)
                        }

                        R.id.app_link_muse -> {
                            showLinkMuseDialog(data)
                        }
                    }
                    return@setOnMenuItemClickListener true
                }
                it.show()
            }
        }
    }
    /** 长按分身 → 关联 Muse 账号：关联后分身卡片下面显示这个账号剩的额度。 */
    private fun showLinkMuseDialog(info: AppInfo) {
        val context = requireContext()
        val cached = MuseUsage.getCached(context)
        if (cached == null || cached.accounts.isEmpty()) {
            showLoading("正在拉额度，请稍等")
            MuseUsage.fetch(context.applicationContext, force = true) { data ->
                if (!isAdded) return@fetch
                hideLoading()
                if (data == null || data.accounts.isEmpty()) {
                    context.toast("拉不到额度数据，检查网络后重试")
                } else {
                    showLinkMuseDialog(info)
                }
            }
            return
        }
        // 现在数据里只有一个账号（小组件那份），直接用它；以后数据扩成多账号再做列表。
        val account = cached.accounts.first()
        val linked = MuseUsage.getLink(context, userID, info.userId, info.packageName)
        MaterialDialog(context).show {
            title(text = "关联 Muse 账号")
            message(text = "${info.name} 关联后，分身下面会显示这个账号剩的额度。\n\n${account.label}" + if (linked != null) "\n\n当前已关联这个账号。" else "")
            if (linked == null) {
                positiveButton(text = "关联") {
                    MuseUsage.setLink(context, userID, info.userId, info.packageName, account.id)
                    mAdapter.notifyDataSetChanged()
                    context.toast("已关联，分身下面会显示剩的额度")
                }
                negativeButton(R.string.cancel)
            } else {
                positiveButton(text = "取消关联") {
                    MuseUsage.clearLink(context, userID, info.userId, info.packageName)
                    mAdapter.notifyDataSetChanged()
                    context.toast("已取消关联")
                }
                negativeButton(text = "关闭")
            }
        }
    }

    private fun initData() {
        viewBinding.stateView.showLoading()
        viewModel.getInstalledApps(userID)
        viewModel.appsLiveData.observe(viewLifecycleOwner) {

            if (it != null) {
                mAdapter.setItems(it)
                if (it.isEmpty()) {
                    viewBinding.stateView.showEmpty()
                } else {
                    viewBinding.stateView.showContent()
                }
            }
        }

        viewModel.resultLiveData.observe(viewLifecycleOwner) {
            // null = 没结果（onStop 清掉的），直接跳过；"" = 装失败的结果，照常关加载框、刷新
            if (it == null) return@observe
            // 空消息也算一次结果：先关加载框、刷新列表；有文字才弹提示（安装失败不弹窗）
            hideLoading()
            if (!TextUtils.isEmpty(it)) {
                requireContext().toast(it)
            }
            viewModel.getInstalledApps(userID)
            scanUser()
        }

        viewModel.launchLiveData.observe(viewLifecycleOwner) {
            it?.run {
                hideLoading()
                if (!it) {
                    toast(R.string.start_fail)
                }
            }
        }

        viewModel.updateSortLiveData.observe(viewLifecycleOwner) {
            if (this::mAdapter.isInitialized) {
                viewModel.updateApkOrder(userID, mAdapter.getItems())
            }
        }
    }

    override fun onStop() {
        super.onStop()
        MuseUsage.removeListener(quotaListener)
        viewModel.resultLiveData.value = null
        viewModel.launchLiveData.value = null
    }

    private fun unInstallApk(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.uninstall_app)
            message(text = getString(R.string.uninstall_app_hint, info.name))
            positiveButton(R.string.done) {
                showLoading()
                viewModel.unInstall(info.packageName, userID, info.userId)
            }
            negativeButton(R.string.cancel)
        }
    }

    /**
     * 强行停止软件
     * @param info AppInfo
     */
    private fun stopApk(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.app_stop)
            message(text = getString(R.string.app_stop_hint,info.name))
            positiveButton(R.string.done) {
                BlackBoxCore.get().stopPackage(info.packageName, info.userId)
                toast(getString(R.string.is_stop,info.name))
            }
            negativeButton(R.string.cancel)
        }
    }

    /**
     * 清除软件数据
     * @param info AppInfo
     */
    private fun clearApk(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.app_clear)
            message(text = getString(R.string.app_clear_hint,info.name))
            positiveButton(R.string.done) {
                showLoading()
                viewModel.clearApkData(info.packageName, info.userId)
            }
            negativeButton(R.string.cancel)
        }
    }

    /** 分身自定义改名：输入框留空 = 恢复自动名（Muse / Muse1、Muse2……） */
    private fun showRenameDialog(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.app_rename)
            input(hintRes = R.string.app_rename, prefill = info.name) { _, input ->
                CloneSpace.setCustomAppName(userID, info.userId, info.packageName, input.toString())
                viewModel.getInstalledApps(userID)
            }
            positiveButton(R.string.done)
            negativeButton(R.string.cancel)
        }
    }

    /**
     * 热修复配置：选择补丁 dex/apk/jar，重启分身后生效
     * @param info AppInfo
     */
    private fun showHotfixDialog(info: AppInfo) {
        hotfixPackage = info.packageName
        hotfixUserId = info.userId
        val patch = BEnvironment.getHotfixPatchFile(info.userId, info.packageName)
        val message = if (patch.isFile) {
            val time =
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(patch.lastModified()))
            getString(R.string.hotfix_dialog_hint_exist, patch.name, "${patch.length() / 1024} KB", time)
        } else {
            getString(R.string.hotfix_dialog_hint_none)
        }
        MaterialDialog(requireContext()).show {
            title(R.string.hotfix_dialog_title)
            message(text = message)
            positiveButton(R.string.hotfix_pick) { pickHotfixPatch() }
            negativeButton(R.string.hotfix_clear) {
                viewModel.clearHotfixPatch(info.userId, info.packageName)
            }
        }
    }

    private fun pickHotfixPatch() {
        if (hotfixPackage == null) return
        try {
            patchPicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            toast(R.string.hotfix_pick_fail)
        }
    }


    fun installApk(source: String, count: Int = 1) {
        showLoading(getString(R.string.creating_clone))
        viewModel.install(source, userID, count)
    }


    private fun scanUser() {
        (requireActivity() as MainActivity).scanUser()
    }

    private fun showLoading(text: String? = null) {
        if(requireActivity() is LoadingActivity){
            (requireActivity() as LoadingActivity).showLoading(text)
        }
    }


    private fun hideLoading() {
        if(requireActivity() is LoadingActivity){
            (requireActivity() as LoadingActivity).hideLoading()
        }
    }


    companion object{
        fun newInstance(userID:Int): AppsFragment {
            val fragment = AppsFragment()
            val bundle = bundleOf("userID" to userID)
            fragment.arguments = bundle
            return fragment
        }
    }



}

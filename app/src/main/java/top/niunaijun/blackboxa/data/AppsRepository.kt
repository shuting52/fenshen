package top.niunaijun.blackboxa.data

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.BlackBoxCore.getPackageManager
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.hotfix.HotfixManager
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackbox.utils.FileUtils
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.app.CloneSpace
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.util.getString
import java.io.File


/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 23:05
 */

class AppsRepository {
    val TAG: String = "AppsRepository"
    private var mInstalledList = mutableListOf<AppInfo>()

    fun previewInstallList() {
        synchronized(mInstalledList) {
            val installedApplications: List<ApplicationInfo> =
                getPackageManager().getInstalledApplications(0)
            val installedList = mutableListOf<AppInfo>()

            for (installedApplication in installedApplications) {
                val file = File(installedApplication.sourceDir)

                if ((installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

                if (!AbiUtils.isSupport(file)) continue

                val isXpModule = BlackBoxCore.get().isXposedModule(file)

                val info = AppInfo(
                    installedApplication.loadLabel(getPackageManager()).toString(),
                    installedApplication.loadIcon(getPackageManager()),
                    installedApplication.packageName,
                    installedApplication.sourceDir,
                    isXpModule
                )
                installedList.add(info)
            }
            this.mInstalledList.clear()
            this.mInstalledList.addAll(installedList)
        }


    }

    fun getInstalledAppList(
        userID: Int,
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        loadingLiveData.postValue(true)
        synchronized(mInstalledList) {
            val blackBoxCore = BlackBoxCore.get()
            Log.d(TAG, mInstalledList.joinToString(","))
            val newInstalledList = mInstalledList.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    CloneSpace.spaceMembers(userID).any { memberId ->
                        blackBoxCore.isInstalled(it.packageName, memberId)
                    }
                )
            }
            appsLiveData.postValue(newInstalledList)
            loadingLiveData.postValue(false)


        }

    }

    fun getInstalledModuleList(
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {

        loadingLiveData.postValue(true)
        synchronized(mInstalledList) {
            val blackBoxCore = BlackBoxCore.get()
            val moduleList = mInstalledList.filter {
                it.isXpModule
            }.map {
                InstalledAppBean(
                    it.name,
                    it.icon,
                    it.packageName,
                    it.sourceDir,
                    blackBoxCore.isInstalledXposedModule(it.packageName)
                )
            }
            appsLiveData.postValue(moduleList)
            loadingLiveData.postValue(false)
        }

    }


    fun getVmInstallList(userId: Int, appsLiveData: MutableLiveData<List<AppInfo>>) {
        val sortListData =
            AppManager.mRemarkSharedPreferences.getString("AppList$userId", "")
        val sortList = sortListData?.split(",") ?: emptyList()

        // 一个空间 = 空间本身 + 它的隐藏克隆用户，同一应用出现多次时自动编号
        val members = CloneSpace.spaceMembers(userId)
        val perMember = members.map { memberId ->
            BlackBoxCore.get().getInstalledApplications(0, memberId).map { app ->
                Triple(app, memberId, app.packageName)
            }
        }
        val totals = mutableMapOf<String, Int>()
        perMember.flatten().forEach { totals[it.third] = (totals[it.third] ?: 0) + 1 }
        val seen = mutableMapOf<String, Int>()
        val appInfoList = mutableListOf<AppInfo>()
        perMember.forEach { list ->
            list.forEach { (app, memberId, pkg) ->
                val index = (seen[pkg] ?: 0) + 1
                seen[pkg] = index
                val label = app.loadLabel(getPackageManager()).toString()
                val name = CloneSpace.displayAppName(
                    userId, memberId, pkg, label, index, totals[pkg] ?: 1
                )
                appInfoList.add(
                    AppInfo(
                        name,
                        app.loadIcon(getPackageManager()),
                        pkg,
                        app.sourceDir,
                        isInstalledXpModule(pkg),
                        memberId
                    )
                )
            }
        }
        // 按保存的顺序排；同一包名的分身排在一起（stable sort 保留成员顺序）
        appInfoList.sortWith(compareBy { sortList.indexOf(it.packageName) })

        appsLiveData.postValue(appInfoList)
    }

    private fun isInstalledXpModule(packageName: String): Boolean {
        BlackBoxCore.get().installedXPModules.forEach {
            if (packageName == it.packageName) {
                return@isInstalledXpModule true
            }
        }

        return false
    }


    fun installApk(
        source: String,
        userId: Int,
        resultLiveData: MutableLiveData<String>,
        count: Int = 1
    ) {
        val blackBoxCore = BlackBoxCore.get()
        try {
            val packageName = resolvePackageName(source)
            var created = 0
            var lastMsg = ""
            // 一键批量分身：装一个、再装下一个；同一个应用已在空间里时，
            // 每多一个就装进一个新创建的隐藏用户，数据完全隔离
            repeat(count.coerceIn(1, 30)) {
                var targetUserId = userId
                if (packageName != null &&
                    CloneSpace.spaceMembers(userId).any { blackBoxCore.isInstalled(packageName, it) }
                ) {
                    targetUserId = CloneSpace.nextCloneUserId()
                }

                val installResult = if (URLUtil.isValidUrl(source)) {
                    val uri = Uri.parse(source)
                    blackBoxCore.installPackageAsUser(uri, targetUserId)
                } else {
                    blackBoxCore.installPackageAsUser(source, targetUserId)
                }

                if (installResult.success) {
                    if (targetUserId != userId) {
                        CloneSpace.addCloneUser(userId, targetUserId)
                    }
                    updateAppSortList(userId, installResult.packageName, true)
                    created++
                } else {
                    lastMsg = installResult.msg
                }
            }
            when {
                created == count -> {
                    if (count == 1) resultLiveData.postValue(getString(R.string.install_success))
                    else resultLiveData.postValue(
                        getString(R.string.install_batch_success, created.toString())
                    )
                }
                created > 0 -> resultLiveData.postValue(
                    getString(R.string.install_batch_partial, created.toString(), (count - created).toString())
                )
                else -> resultLiveData.postValue(getString(R.string.install_fail, lastMsg))
            }
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.install_fail, e.message ?: e.javaClass.simpleName))
        }
        scanUser()
    }

    /** 安装前先读出包名（包名 / 文件路径 / content URI 三种来源都支持） */
    private fun resolvePackageName(source: String): String? {
        return try {
            val pm = getPackageManager()
            when {
                URLUtil.isValidUrl(source) -> {
                    val tmp = File(
                        App.getContext().cacheDir,
                        "clone-check-${System.currentTimeMillis()}.apk"
                    )
                    try {
                        App.getContext().contentResolver.openInputStream(Uri.parse(source))
                            ?.use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                        pm.getPackageArchiveInfo(tmp.absolutePath, 0)?.packageName
                    } finally {
                        tmp.delete()
                    }
                }
                File(source).isFile -> pm.getPackageArchiveInfo(source, 0)?.packageName
                else -> try {
                    pm.getPackageInfo(source, 0)?.packageName
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 卸载一个分身（不发结果）：单个卸载和批量删除共用 */
    private fun uninstallOne(packageName: String, userID: Int, realUserId: Int): Boolean {
        return try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, realUserId)
            // 空间里还有同包名的分身时，保留它在排序列表里的位置
            val stillThere = CloneSpace.spaceMembers(userID).any {
                BlackBoxCore.get().isInstalled(packageName, it)
            }
            if (!stillThere) {
                updateAppSortList(userID, packageName, false)
            }
            // 隐藏克隆用户里没应用了就整个删掉，不留空壳
            if (CloneSpace.isCloneUser(realUserId) &&
                BlackBoxCore.get().getInstalledApplications(0, realUserId).isEmpty()
            ) {
                BlackBoxCore.get().deleteUser(realUserId)
                CloneSpace.removeCloneUser(realUserId)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun unInstall(
        packageName: String,
        userID: Int,
        realUserId: Int,
        resultLiveData: MutableLiveData<String>
    ) {
        val ok = uninstallOne(packageName, userID, realUserId)
        scanUser()
        resultLiveData.postValue(
            getString(if (ok) R.string.uninstall_success else R.string.uninstall_fail)
        )
    }

    /** 批量删除：一个一个卸，最后只报一次结果 */
    fun unInstallBatch(
        items: List<AppInfo>,
        userID: Int,
        resultLiveData: MutableLiveData<String>
    ) {
        var ok = 0
        items.forEach { info ->
            if (uninstallOne(info.packageName, userID, info.userId)) ok++
        }
        val fail = items.size - ok
        scanUser()
        resultLiveData.postValue(
            if (fail == 0) getString(R.string.batch_delete_done, ok.toString())
            else getString(R.string.batch_delete_partial, ok.toString(), fail.toString())
        )
    }


    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        val result = BlackBoxCore.get().launchApk(packageName, userId)
        launchLiveData.postValue(result)
    }


    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().clearPackage(packageName, userID)
            resultLiveData.postValue(getString(R.string.clear_success))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.clear_fail))
        }
    }

    /**
     * 热修复补丁：宿主把用户选择的 dex 拷进 blackbox 根目录 hotfix/u<userId>/<pkg>.dex，
     * 分身进程每次启动时由 HotfixManager 前插到应用类加载器的 dexElements
     */
    fun getHotfixPatch(userId: Int, packageName: String): File? {
        val patch = BEnvironment.getHotfixPatchFile(userId, packageName)
        return if (patch.isFile) patch else null
    }

    fun saveHotfixPatch(userId: Int, packageName: String, uri: Uri, resultLiveData: MutableLiveData<String>) {
        try {
            val patch = BEnvironment.getHotfixPatchFile(userId, packageName)
            FileUtils.mkdirs(patch.parentFile!!.absolutePath)
            App.getContext().contentResolver.openInputStream(uri)!!.use { input ->
                patch.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // 覆盖旧补丁后清掉旧 odex 缓存，避免新旧内容不一致
            HotfixManager.clearOdexCache(packageName, userId)
            resultLiveData.postValue(getString(R.string.hotfix_saved))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.hotfix_save_fail, e.message ?: e.javaClass.simpleName))
        }
    }

    fun clearHotfixPatch(userId: Int, packageName: String, resultLiveData: MutableLiveData<String>) {
        try {
            if (!BEnvironment.getHotfixPatchFile(userId, packageName).exists()) {
                resultLiveData.postValue(getString(R.string.hotfix_none))
                return
            }
            HotfixManager.clearPatch(packageName, userId)
            resultLiveData.postValue(getString(R.string.hotfix_cleared))
        } catch (e: Exception) {
            resultLiveData.postValue(getString(R.string.hotfix_clear_fail))
        }
    }

    /**
     * 倒序递归扫描用户，
     * 如果用户是空的，就删除用户，删除用户备注，删除应用排序列表
     */
    private fun scanUser() {
        val blackBoxCore = BlackBoxCore.get()
        val realIds = CloneSpace.realUserIds()

        if (realIds.isNotEmpty()) {
            val id = realIds.last()
            if (blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
                blackBoxCore.deleteUser(id)
                AppManager.mRemarkSharedPreferences.edit {
                    remove("Remark$id")
                    remove("AppList$id")
                    remove("CloneUsers_$id")
                }
                scanUser()
                return
            }
        }

        // 空的隐藏克隆用户整个删掉，不留空壳
        blackBoxCore.users.map { it.id }.filter { CloneSpace.isCloneUser(it) }.forEach { hid ->
            if (blackBoxCore.getInstalledApplications(0, hid).isEmpty()) {
                blackBoxCore.deleteUser(hid)
                CloneSpace.removeCloneUser(hid)
                AppManager.mRemarkSharedPreferences.edit { remove("AppList$hid") }
            }
        }
    }


    /**
     * 更新排序列表
     * @param userID Int
     * @param pkg String
     * @param isAdd Boolean true是添加，false是移除
     */
    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {

        val savedSortList =
            AppManager.mRemarkSharedPreferences.getString("AppList$userID", "")

        val sortList = linkedSetOf<String>()
        if (savedSortList != null) {
            sortList.addAll(savedSortList.split(","))
        }

        if (isAdd) {
            sortList.add(pkg)
        } else {
            sortList.remove(pkg)
        }

        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID", sortList.joinToString(","))
        }

    }

    /**
     * 保存排序后的apk顺序
     */
    fun updateApkOrder(userID: Int, dataList: List<AppInfo>) {
        AppManager.mRemarkSharedPreferences.edit {
            putString("AppList$userID",
                dataList.joinToString(",") { it.packageName })
        }

    }

}

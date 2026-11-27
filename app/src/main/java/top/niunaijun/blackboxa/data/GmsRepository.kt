package top.niunaijun.blackboxa.data

import androidx.lifecycle.MutableLiveData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.GmsCore
import top.niunaijun.blackbox.entity.pm.InstallResult
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.CloneSpace
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.util.getString

/**
 *
 * @Description:
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:14
 */
class GmsRepository {


    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        CloneSpace.realUserIds().forEach { userId ->
            val userName = CloneSpace.getRemark(userId)
            val isInstalled = BlackBoxCore.get().isInstallGms(userId)
            val bean = GmsBean(userId, userName, isInstalled)
            userList.add(bean)
        }

        mInstalledLiveData.postValue(userList)
    }

    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        // 手机上有谷歌环境：走原来的路（把手机上的谷歌服务克隆进空间）
        // 手机上没有谷歌环境：装包里带的 microG 开源版，不用手机上有谷歌服务
        val isMicroG = !BlackBoxCore.get().isSupportGms
        val installResult = if (isMicroG) {
            installMicroG(userID)
        } else {
            BlackBoxCore.get().installGms(userID)
        }

        val result = if (installResult.success) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, installResult.msg)
        }

        val bean = GmsInstallBean(userID, installResult.success, result, isMicroG)
        mUpdateInstalledLiveData.postValue(bean)
    }

    /** microG 开源版谷歌服务（GmsCore + GsfProxy + FakeStore），直接装进这个空间。
     *  三个包的包名和原生谷歌服务一样（com.google.android.gms / gsf / vending），
     *  所以装好后 isInstallGms 照常认得出来，卸载也走原来的路。 */
    private fun installMicroG(userId: Int): InstallResult {
        val blackBoxCore = BlackBoxCore.get()
        val context = BlackBoxCore.getContext()
        val dir = File(context.cacheDir, "gms")
        if (!dir.exists()) dir.mkdirs()
        val apks = listOf(
            "gmscore.apk" to GmsCore.GMS_PKG,
            "gsfproxy.apk" to GmsCore.GSF_PKG,
            "fakestore.apk" to GmsCore.VENDING_PKG
        )
        val newlyInstalled = mutableListOf<String>()
        for ((asset, pkg) in apks) {
            if (blackBoxCore.isInstalled(pkg, userId)) continue
            val out = File(dir, asset)
            try {
                context.assets.open("gms/$asset").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                val r = InstallResult()
                r.success = false
                r.msg = e.message ?: "copy failed"
                return r
            }
            val r = blackBoxCore.installPackageAsUser(out.absolutePath, userId)
            if (!r.success) {
                // 有一个装不上就撤掉本次新装上的，不动之前就装好的整套
                newlyInstalled.forEach { blackBoxCore.uninstallPackageAsUser(it, userId) }
                return r
            }
            newlyInstalled.add(pkg)
        }
        return InstallResult()
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID,isSuccess,result)

        mUpdateInstalledLiveData.postValue(bean)
    }
}

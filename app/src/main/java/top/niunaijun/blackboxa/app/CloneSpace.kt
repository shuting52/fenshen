package top.niunaijun.blackboxa.app

import androidx.core.content.edit
import top.niunaijun.blackbox.BlackBoxCore

/**
 * 分身空间：界面上的一页（一个空间）由一个真实用户 + 若干隐藏克隆用户组成。
 * 同一个应用在一个空间里出现多次时，每多一个就装进一个新创建的隐藏用户，
 * 每个隐藏用户都是独立的数据隔离，账号互不干扰。
 * 隐藏用户 id 从 1000 起，永远不会出现在空间列表里。
 */
object CloneSpace {

    const val CLONE_USER_BASE = 1000

    fun isCloneUser(userId: Int) = userId >= CLONE_USER_BASE

    /** 界面上显示的真实空间 id（按 id 排序） */
    fun realUserIds(): List<Int> =
        BlackBoxCore.get().users.map { it.id }.filter { it < CLONE_USER_BASE }.sorted()

    /** 某空间的全部成员：空间本身 + 它的隐藏克隆用户（按创建时间排序），顺带清理已不存在的成员 */
    fun spaceMembers(spaceId: Int): List<Int> {
        val prefs = AppManager.mRemarkSharedPreferences
        val stored = prefs.getString("CloneUsers_$spaceId", "")
            ?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val existing = BlackBoxCore.get().users.map { it.id }.toSet()
        val valid = stored.filter { it in existing }
        if (valid.size != stored.size) {
            prefs.edit { putString("CloneUsers_$spaceId", valid.joinToString(",")) }
        }
        return listOf(spaceId) + valid
    }

    fun nextCloneUserId(): Int {
        val ids = BlackBoxCore.get().users.map { it.id }
        return maxOf(CLONE_USER_BASE, (ids.maxOrNull() ?: 0) + 1)
    }

    fun addCloneUser(spaceId: Int, cloneUserId: Int) {
        val members = spaceMembers(spaceId).filter { it != spaceId }.toMutableList()
        if (!members.contains(cloneUserId)) members.add(cloneUserId)
        AppManager.mRemarkSharedPreferences.edit {
            putString("CloneUsers_$spaceId", members.joinToString(","))
            putInt("CloneOwner_$cloneUserId", spaceId)
        }
    }

    /** 找到隐藏用户所属的空间并从映射里移除，返回空间 id（没有则返回 null） */
    fun removeCloneUser(cloneUserId: Int): Int? {
        val prefs = AppManager.mRemarkSharedPreferences
        val spaceId = prefs.getInt("CloneOwner_$cloneUserId", -1).takeIf { it >= 0 } ?: return null
        val members = spaceMembers(spaceId).filter { it != spaceId && it != cloneUserId }
        prefs.edit {
            putString("CloneUsers_$spaceId", members.joinToString(","))
            remove("CloneOwner_$cloneUserId")
            // 该克隆名下的自定义名一并清掉，否则 id 复用后新克隆会继承旧名字
            prefs.all.keys
                .filter { it.startsWith("CloneAppName|$spaceId|$cloneUserId|") }
                .forEach { remove(it) }
        }
        return spaceId
    }

    /** 删掉一个空间的全部记录：备注、克隆用户映射、它名下分身的自定义名 */
    fun removeSpace(spaceId: Int) {
        val prefs = AppManager.mRemarkSharedPreferences
        val members = prefs.getString("CloneUsers_$spaceId", "")
            ?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        prefs.edit {
            remove("Remark$spaceId")
            remove("AppList$spaceId")
            remove("CloneUsers_$spaceId")
            members.forEach { remove("CloneOwner_$it") }
            prefs.all.keys
                .filter { it.startsWith("CloneAppName|$spaceId|") }
                .forEach { remove(it) }
        }
    }

    /** 空间默认名：分身空间1️⃣、分身空间2️⃣…… */
    fun defaultRemark(userId: Int): String = "分身空间${userId + 1}\uFE0F\u20E3"

    /** 当前备注：没存过、为空、或是旧的 "User N" 时，自动换成新默认名并存下来 */
    fun getRemark(userId: Int): String {
        val prefs = AppManager.mRemarkSharedPreferences
        val stored = prefs.getString("Remark$userId", null)
        if (stored.isNullOrEmpty() || stored.matches(Regex("^User \\d+$"))) {
            val def = defaultRemark(userId)
            prefs.edit { putString("Remark$userId", def) }
            return def
        }
        return stored
    }

    private fun customNameKey(spaceId: Int, realUserId: Int, packageName: String) =
        "CloneAppName|$spaceId|$realUserId|$packageName"

    fun getCustomAppName(spaceId: Int, realUserId: Int, packageName: String): String? =
        AppManager.mRemarkSharedPreferences.getString(
            customNameKey(spaceId, realUserId, packageName), null
        )

    fun setCustomAppName(spaceId: Int, realUserId: Int, packageName: String, name: String) {
        AppManager.mRemarkSharedPreferences.edit {
            if (name.isBlank()) remove(customNameKey(spaceId, realUserId, packageName))
            else putString(customNameKey(spaceId, realUserId, packageName), name)
        }
    }

    /** 分身显示名：自定义名优先；同一应用在空间里出现多次时自动加序号：Muse1、Muse2…… */
    fun displayAppName(
        spaceId: Int,
        realUserId: Int,
        packageName: String,
        label: String,
        index: Int,
        total: Int
    ): String {
        getCustomAppName(spaceId, realUserId, packageName)?.let { return it }
        return if (total > 1) "$label$index" else label
    }
}

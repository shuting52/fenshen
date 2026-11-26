package top.niunaijun.blackboxa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Muse 额度：从 GitHub 上的 muse-usage.json 拉取（和小组件同一份数据）。
 * 分身长按「关联 Muse 账号」后，分身卡片下面显示这个账号剩的额度。
 *
 * 注意：数据现在只有一份账号（小组件那份）；别的账号的数据不在这个文件里，
 * 要显示多个账号，得先把那份数据扩成多账号（需要各账号的会话，不是分身这边能变的）。
 */
object MuseUsage {

    const val DATA_URL = "https://raw.githubusercontent.com/shuting52/10-05landezhaole/main/muse-usage.json"

    private const val PREFS = "muse_usage"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val LINK_PREFS = "muse_links"
    private const val FETCH_INTERVAL_MS = 60 * 60 * 1000L

    data class Account(
        val id: String,
        val plan: String,
        val usedPct: Int,
        val remainingPct: Int,
        val resetIso: String,
        val resetText: String,
        val extraRemainingPct: Int?,
        val extraLeft: String?
    ) {
        /** 12月2日（reset_iso 解析失败时用 reset_text 原样） */
        val resetShort: String
            get() {
                val m = Regex("""\d{4}-(\d{2})-(\d{2})""").find(resetIso)
                if (m != null) {
                    val month = m.groupValues[1].toIntOrNull() ?: return resetText
                    val day = m.groupValues[2].toIntOrNull() ?: return resetText
                    return "${month}月${day}日"
                }
                val m2 = Regex("""([A-Z][a-z]{2}) (\d+)""").find(resetText)
                if (m2 != null) {
                    val mon = mapOf(
                        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4,
                        "May" to 5, "Jun" to 6, "Jul" to 7, "Aug" to 8,
                        "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12
                    )[m2.groupValues[1]] ?: return resetText
                    return "${mon}月${m2.groupValues[2]}日"
                }
                return resetText
            }

        /** 免费版 · 已用 76% · 12月2日重置 · 额外 27B（跟叠号卡片一致） */
        val label: String
            get() {
                val planName = if (plan == "free") "免费版" else "订阅版"
                val useText = if (usedPct >= 0) "已用 $usedPct%" else "剩 $remainingPct%"
                val sb = StringBuilder("$planName · $useText · ${resetShort}重置")
                if (extraLeft != null) sb.append(" · 额外 $extraLeft")
                return sb.toString()
            }
    }

    data class UsageData(val accounts: List<Account>, val fetchedAt: Long)

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun getCached(context: Context): UsageData? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        return parse(json)?.copy(fetchedAt = fetchedAt)
    }

    /** 拉取；force=true 忽略 1 小时节流。回调一定在主线程；拉不到时回旧缓存（没有就回 null）。 */
    fun fetch(context: Context, force: Boolean = false, onDone: ((UsageData?) -> Unit)? = null) {
        val cached = getCached(context)
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < FETCH_INTERVAL_MS) {
            Handler(Looper.getMainLooper()).post { onDone?.invoke(cached) }
            return
        }
        Thread {
            var fresh: UsageData? = null
            try {
                val conn = URL(DATA_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    fresh = parse(text)
                    if (fresh != null) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_JSON, text)
                            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                            .apply()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                CrashLog.bread("MuseUsage fetch fail: ${e.javaClass.simpleName}")
            }
            val result = fresh ?: cached
            Handler(Looper.getMainLooper()).post {
                if (fresh != null) listeners.forEach { it() }
                onDone?.invoke(result)
            }
        }.start()
    }

    /** 解析：兼容现在这种单账号顶层字段，也兼容以后加的 accounts 数组。 */
    fun parse(text: String): UsageData? {
        return try {
            val root = JSONObject(text)
            val list = mutableListOf<Account>()
            if (root.has("accounts")) {
                val arr = root.getJSONArray("accounts")
                for (i in 0 until arr.length()) {
                    list.add(parseAccount("acc$i", arr.getJSONObject(i)))
                }
            } else {
                list.add(parseAccount("main", root))
            }
            UsageData(list, System.currentTimeMillis())
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAccount(id: String, o: JSONObject): Account {
        val used = o.optInt("weekly_used_pct", -1)
        val remaining = o.optInt(
            "weekly_remaining_pct",
            if (used >= 0) 100 - used else -1
        )
        val extraLeftRaw = o.optString("additional_left", "").trim()
        val extraLeft = if (extraLeftRaw.isEmpty()) null
        else extraLeftRaw.replace(" tokens left", "").trim()
        return Account(
            id = id,
            plan = o.optString("plan", "free"),
            usedPct = used,
            remainingPct = remaining,
            resetIso = o.optString("reset_iso", ""),
            resetText = o.optString("reset_text", ""),
            extraRemainingPct = if (o.has("additional_remaining_pct")) o.optInt("additional_remaining_pct") else null,
            extraLeft = extraLeft
        )
    }

    // 分身和账号的关联：存在 muse_links，key 是 空间|分身用户|包名，值是账号 id。
    fun linkKey(spaceId: Int, userId: Int, packageName: String) =
        "MuseAccount|$spaceId|$userId|$packageName"

    fun getLink(context: Context, spaceId: Int, userId: Int, packageName: String): String? {
        return context.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE)
            .getString(linkKey(spaceId, userId, packageName), null)
    }

    fun setLink(context: Context, spaceId: Int, userId: Int, packageName: String, accountId: String) {
        context.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE).edit()
            .putString(linkKey(spaceId, userId, packageName), accountId)
            .apply()
    }

    fun clearLink(context: Context, spaceId: Int, userId: Int, packageName: String) {
        context.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE).edit()
            .remove(linkKey(spaceId, userId, packageName))
            .apply()
    }

    // 账号头像：她自己从相册选的照片，按账号 id 存一份。
    // 叠号/Muse 的头像拉不到（照片在叠号自己的存储里，分身读不到），只能她自己选一张。
    private const val AVATAR_PREFIX = "muse_avatar_"

    private fun avatarFile(context: Context, accountId: String) =
        File(context.filesDir, "$AVATAR_PREFIX$accountId.jpg")

    fun hasAvatar(context: Context, accountId: String): Boolean =
        avatarFile(context, accountId).exists()

    private val avatarBitmapCache = mutableMapOf<String, Bitmap?>()

    /** 账号头像原图（圆图由调用方包一层 RoundedBitmapDrawable）；没设过回 null。 */
    fun getAvatarBitmap(context: Context, accountId: String): Bitmap? {
        avatarBitmapCache[accountId]?.let { return it }
        val f = avatarFile(context, accountId)
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        avatarBitmapCache[accountId] = bmp
        return bmp
    }

    /** 把相册里选的图存成账号头像（最长边压到 256px）。后台线程存，回调在主线程。 */
    fun saveAvatar(context: Context, accountId: String, uri: Uri, onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            var ok = false
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxSide / sample > 256) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) {
                    FileOutputStream(avatarFile(context, accountId)).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    avatarBitmapCache[accountId] = bmp
                    ok = true
                }
            } catch (e: Exception) {
                CrashLog.bread("MuseUsage saveAvatar fail: ${e.javaClass.simpleName}")
            }
            Handler(Looper.getMainLooper()).post { onDone?.invoke(ok) }
        }.start()
    }

    fun clearAvatar(context: Context, accountId: String) {
        avatarBitmapCache.remove(accountId)
        avatarFile(context, accountId).delete()
    }
}

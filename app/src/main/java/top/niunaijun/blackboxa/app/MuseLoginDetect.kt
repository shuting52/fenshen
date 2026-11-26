package top.niunaijun.blackboxa.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 认分身里登的 Muse 账号：直接读分身里 Muse 应用自己的数据。
 *
 * 分身和宿主是同一个身份，分身的数据就存在本软件自己的文件里，
 * 宿主这边能直接读到。Muse 把登录账号（邮箱、头像地址）存在
 * shared_prefs 里时，这里能认出来。
 *
 * 用途：
 * - 没登账号的分身不显示额度（没登录哪来的额度）。
 * - 登了账号的分身，卡片上直接显示那个账号的头像，不用手动设。
 *
 * 读不到（账号存在加密的地方）就回 null，调用方按“没扫到”处理，
 * 不要把读不到当成没登录。
 */
object MuseLoginDetect {
    const val MUSE_PKG = "com.facebook.aura"

    private val EMAIL_RE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val NON_USER =
        listOf("noreply", "no-reply", "donotreply", "support@", "help@", "info@", "admin@", "feedback@")
    private val KEYED_STRING_RE = Regex("""<string name="([^"]*)">([^<]*)</string>""")
    private val AVATAR_KEY_RE =
        Regex("""<string name="[^"]*(avatar|profile_pic|profilepic|photo|picture|head)[^"]*">(https?://[^<]+)</string>""")
    private val IMG_URL_RE = Regex("""https?://[^\s"<>]+\.(?:jpg|jpeg|png|webp)(?:[?#][^\s"<>]*)?""")

    data class Detected(val email: String?, val avatarUrl: String?)

    private val cache = mutableMapOf<String, Detected?>()
    private val avatarCache = mutableMapOf<String, Bitmap?>()

    fun key(packageName: String, userId: Int) = "$packageName|$userId"

    fun isMuseClone(packageName: String, appName: String): Boolean =
        packageName == MUSE_PKG || appName.startsWith("Muse", ignoreCase = true)

    /** 这个分身扫过没有（扫过但没找到账号，和没扫，是两回事）。 */
    fun isScanned(packageName: String, userId: Int): Boolean = cache.containsKey(key(packageName, userId))

    fun getCached(packageName: String, userId: Int): Detected? = cache[key(packageName, userId)]

    fun getAvatarBitmap(packageName: String, userId: Int): Bitmap? = avatarCache[key(packageName, userId)]

    fun clearCache() {
        cache.clear()
        avatarCache.clear()
    }

    /**
     * 扫一个分身的 Muse 数据，认里面登的账号。
     * 必须在后台线程调（读文件 + 可能下载头像）。
     */
    fun detect(packageName: String, userId: Int): Detected? {
        val k = key(packageName, userId)
        if (cache.containsKey(k)) return cache[k]
        val result = scan(packageName, userId)
        cache[k] = result
        // 扫到头像地址就顺手下载，卡片上直接显示登录账号的头像，不用她手动设。
        result?.avatarUrl?.let { url ->
            avatarCache[k] = downloadAvatar(url)
        }
        return result
    }

    private fun scan(packageName: String, userId: Int): Detected? {
        val prefsDir = File(BEnvironment.getDataDir(packageName, userId), "shared_prefs")
        if (!prefsDir.isDirectory) return null
        val files = prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return null
        var email: String? = null
        var avatarUrl: String? = null
        for (f in files) {
            val text = try {
                f.readText()
            } catch (e: Exception) {
                continue
            }
            if (email == null) email = findEmail(text)
            if (avatarUrl == null) avatarUrl = findAvatarUrl(text)
            if (email != null && avatarUrl != null) break
        }
        if (email == null && avatarUrl == null) return null
        return Detected(email, avatarUrl)
    }

    private fun findEmail(text: String): String? {
        // 先找 key 里带 email/account/user 的那条
        for (m in KEYED_STRING_RE.findAll(text)) {
            val name = m.groupValues[1].toLowerCase()
            val value = m.groupValues[2]
            if ((name.contains("email") || name.contains("account") || name.contains("user") || name.contains("mail"))
                && EMAIL_RE.matches(value) && NON_USER.none { value.toLowerCase().contains(it) }
            ) {
                return value
            }
        }
        // 再找任意邮箱
        return EMAIL_RE.findAll(text)
            .map { it.value }
            .firstOrNull { e -> NON_USER.none { e.toLowerCase().contains(it) } }
    }

    private fun findAvatarUrl(text: String): String? {
        AVATAR_KEY_RE.find(text)?.let { return it.groupValues[2] }
        return IMG_URL_RE.find(text)?.value
    }

    /** 下载头像（最长边压到 256px），下不到回 null。 */
    private fun downloadAvatar(url: String): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 2 * 1024 * 1024) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            CrashLog.bread("MuseLoginDetect avatar fail: ${e.javaClass.simpleName}")
            null
        }
    }
}

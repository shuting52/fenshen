package top.niunaijun.blackboxa.app

import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * 从分身自己的数据里找 Muse 缓存下来的订阅额度。
 * 思路跟叠号一样：读账号自己的数据，不靠外部文件。叠号是在网页会话里调 Muse 自己的订阅接口拿
 * usage.percentUsed / resetsAt / topup；安卓版 Muse 调的是同一个接口（GET hatch-api.meta.ai/hatch/subscription），
 * 返回的也是同一套 subscription 对象，App 的 HTTP 缓存 / 数据文件里可能留着这份响应的原文。
 * 只读不写：扫不到就回 null，卡片照旧走关联数据；扫到了，分身下面直接显示自己的数，不用关联。
 * 只能在后台线程调。
 */
object MuseQuotaCache {
    private val cache = mutableMapOf<String, MuseUsage.Account?>()
    private val lock = Any()

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_FILES = 2000
    private const val MAX_GZIP_OUT = 4 * 1024 * 1024

    private val USED_RE = Pattern.compile("\"percentUsed\"\\s*:\\s*(\\d+)|\"percent_used\"\\s*:\\s*(\\d+)")
    private val RESET_RE = Pattern.compile("\"resetsAt\"\\s*:\\s*\"([^\"]+)\"|\"resets_at\"\\s*:\\s*\"([^\"]+)\"")
    private val TOPUP_BAL_RE = Pattern.compile("\"topupBalance\"\\s*:\\s*(\\d+)|\"topup_balance\"\\s*:\\s*(\\d+)")
    private val TOPUP_TOT_RE = Pattern.compile("\"topupTotal\"\\s*:\\s*(\\d+)|\"topup_total\"\\s*:\\s*(\\d+)")
    private val PLAN_RE = Pattern.compile("\"subscriptionType\"\\s*:\\s*\"(\\w+)\"|\"subscription_type\"\\s*:\\s*\"(\\w+)\"")

    fun getCached(packageName: String, userId: Int): MuseUsage.Account? {
        synchronized(lock) { return cache[key(packageName, userId)] }
    }

    fun clearCache() {
        synchronized(lock) { cache.clear() }
    }

    private fun key(packageName: String, userId: Int) = "$packageName|$userId"

    /** 后台线程调。扫分身数据目录，找缓存的订阅响应；找不到回 null（也会记下来，不重复扫）。 */
    fun scan(packageName: String, userId: Int): MuseUsage.Account? {
        val k = key(packageName, userId)
        synchronized(lock) { if (cache.containsKey(k)) return cache[k] }
        var result: MuseUsage.Account? = null
        var files = 0
        var bytes = 0L
        try {
            val root = BEnvironment.getDataDir(packageName, userId)
            if (root.isDirectory) {
                for (f in root.walkTopDown().maxDepth(6)) {
                    if (files >= MAX_FILES || bytes >= MAX_TOTAL_BYTES) break
                    if (!f.isFile) continue
                    val path = f.path
                    if (path.contains("/lib/") || path.endsWith(".so")) continue
                    val len = f.length()
                    if (len <= 0 || len > MAX_FILE_BYTES) continue
                    files++
                    bytes += len
                    try {
                        val data = f.readBytes()
                        result = findInBytes(data, k)
                        if (result != null) {
                            CrashLog.bread("quota-cache $k: found in $path used=${result.usedPct}")
                            break
                        }
                    } catch (e: Exception) {
                        // 单个文件读不了就跳过
                    }
                }
            }
        } catch (e: Exception) {
            CrashLog.bread("quota-cache $k: scan error ${e.message}")
        }
        if (result == null) CrashLog.bread("quota-cache $k: none (files=$files)")
        synchronized(lock) { cache[k] = result }
        return result
    }

    private fun findInBytes(data: ByteArray, k: String): MuseUsage.Account? {
        findInText(String(data, Charsets.UTF_8), k)?.let { return it }
        // 有些缓存体是 gzip 的（可能嵌在文件中间），从每个 gzip 魔数处试着解出来再找。
        var off = 0
        var tries = 0
        while (tries < 3) {
            val idx = indexOfGzip(data, off)
            if (idx < 0) break
            tries++
            off = idx + 2
            try {
                val out = ByteArrayOutputStream()
                val gz = GZIPInputStream(ByteArrayInputStream(data, idx, data.size - idx))
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_GZIP_OUT) break
                    out.write(buf, 0, n)
                }
                gz.close()
                findInText(String(out.toByteArray(), Charsets.UTF_8), k)?.let { return it }
            } catch (e: Exception) {
                // 不是完整 gzip 流就跳过
            }
        }
        return null
    }

    private fun indexOfGzip(data: ByteArray, from: Int): Int {
        var i = if (from < 0) 0 else from
        while (i + 1 < data.size) {
            if (data[i] == 0x1f.toByte() && data[i + 1] == 0x8b.toByte()) return i
            i++
        }
        return -1
    }

    /** 同一段文本里必须同时出现已用百分比和重置时间/额外额度其一，才算命中（防别的 JSON 误撞）。 */
    private fun findInText(text: String, k: String): MuseUsage.Account? {
        val used = firstInt(USED_RE, text) ?: return null
        val reset = firstString(RESET_RE, text)
        val bal = firstLong(TOPUP_BAL_RE, text)
        val tot = firstLong(TOPUP_TOT_RE, text)
        if (reset == null && bal == null) return null
        val planRaw = firstString(PLAN_RE, text)
        val plan = if (planRaw != null && planRaw.toLowerCase().contains("paid")) "paid" else "free"
        val extraPct = if (bal != null && tot != null && tot > 0) ((bal * 100L / tot).toInt()) else null
        val extraLeft = if (bal != null) formatBalance(bal) else null
        return MuseUsage.Account(
            id = "cache:$k",
            plan = plan,
            usedPct = used,
            remainingPct = 100 - used,
            resetIso = reset ?: "",
            resetText = reset ?: "",
            extraRemainingPct = extraPct,
            extraLeft = extraLeft
        )
    }

    private fun firstInt(re: Pattern, text: String): Int? {
        val m = re.matcher(text)
        if (!m.find()) return null
        for (i in 1..m.groupCount()) {
            val g = m.group(i)
            if (g != null) return g.toIntOrNull()
        }
        return null
    }

    private fun firstLong(re: Pattern, text: String): Long? {
        val m = re.matcher(text)
        if (!m.find()) return null
        for (i in 1..m.groupCount()) {
            val g = m.group(i)
            if (g != null) return g.toLongOrNull()
        }
        return null
    }

    private fun firstString(re: Pattern, text: String): String? {
        val m = re.matcher(text)
        if (!m.find()) return null
        for (i in 1..m.groupCount()) {
            val g = m.group(i)
            if (g != null) return g
        }
        return null
    }

    private fun formatBalance(v: Long): String {
        return when {
            v >= 1_000_000_000L -> trimNum(v / 1_000_000_000.0) + "B"
            v >= 1_000_000L -> trimNum(v / 1_000_000.0) + "M"
            else -> v.toString()
        }
    }

    private fun trimNum(d: Double): String {
        val s = String.format(Locale.US, "%.1f", d)
        return if (s.endsWith(".0")) s.substring(0, s.length - 2) else s
    }
}

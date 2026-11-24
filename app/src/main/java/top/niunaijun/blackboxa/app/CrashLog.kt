package top.niunaijun.blackboxa.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 闪退诊断（2.3.13 诊断版专用）：记崩溃堆栈 + 面包屑，写到 App 专属外部目录。
 * 文件位置：手机存储/Android/data/mis.shuting.fensheng/files/fenshen-crash.log
 * 记日志本身永远不许再引起崩溃，所以处处 try/catch。
 */
object CrashLog {
    private const val FILE_NAME = "fenshen-crash.log"
    private const val MAX_SIZE = 256 * 1024L
    private val logged = AtomicBoolean(false)

    fun file(): File? {
        return try {
            File(App.getContext().getExternalFilesDir(null), FILE_NAME)
        } catch (e: Exception) {
            null
        }
    }

    /** 面包屑：走到哪一步记一行，崩了也能看出死在哪 */
    fun bread(msg: String) {
        try {
            val f = file() ?: return
            if (f.length() > MAX_SIZE) f.delete()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            f.appendText("$ts $msg\n")
        } catch (e: Exception) {
        }
    }

    /** 装全局崩溃处理器：写堆栈，然后交给原来的处理器（系统该弹崩溃框还弹） */
    fun install() {
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                try {
                    if (logged.compareAndSet(false, true)) {
                        val f = file()
                        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        f?.appendText("\n===== CRASH $ts thread=${thread.name} =====\n")
                        f?.appendText(Log.getStackTraceString(e))
                        f?.appendText("\n")
                    }
                } catch (ignored: Exception) {
                }
                try {
                    prev?.uncaughtException(thread, e)
                } catch (ignored: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    /** 主线程看门狗：主线程卡住超过 4 秒记一行 */
    fun startWatchdog() {
        try {
            val main = Handler(Looper.getMainLooper())
            Thread {
                var last = System.currentTimeMillis()
                val tick = object : Runnable {
                    override fun run() {
                        last = System.currentTimeMillis()
                        main.postDelayed(this, 1000)
                    }
                }
                main.post(tick)
                while (true) {
                    Thread.sleep(1000)
                    val gap = System.currentTimeMillis() - last
                    if (gap > 4000) {
                        bread("WATCHDOG 主线程卡住 ${gap}ms")
                        last = System.currentTimeMillis()
                    }
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
        }
    }
}

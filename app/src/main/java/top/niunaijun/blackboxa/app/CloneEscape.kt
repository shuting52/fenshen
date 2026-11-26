package top.niunaijun.blackboxa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.MainActivity

/**
 * 分身逃生通知：分身打开后常驻一条通知，点一下就回到分身首页。
 *
 * 有些应用（比如 Cue 的登录页）会吞掉返回键，卡住了出不来，只能杀后台；
 * 这条通知就是逃生口，任何时候下拉点一下就能出来，不用杀软件。
 * 回到首页后通知自动消失。
 */
object CloneEscape {
    const val CHANNEL_ID = "clone_escape"
    const val NOTIF_ID = 1001
    const val EXTRA_STOP_PKG = "escape_stop_pkg"
    const val EXTRA_STOP_USER = "escape_stop_user"

    private fun flags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f = f or PendingIntent.FLAG_IMMUTABLE
        }
        return f
    }

    fun show(context: Context, packageName: String, userId: Int, appName: String) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.clone_escape_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val homeIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val homePending = PendingIntent.getActivity(appContext, 1, homeIntent, flags())
        val stopIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_STOP_PKG, packageName)
            putExtra(EXTRA_STOP_USER, userId)
        }
        val stopPending = PendingIntent.getActivity(appContext, 2, stopIntent, flags())
        val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sakuga_burst)
            .setContentTitle("分身运行中：$appName")
            .setContentText(appContext.getString(R.string.clone_escape_text))
            .setContentIntent(homePending)
            .addAction(0, appContext.getString(R.string.clone_escape_stop), stopPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    fun hide(context: Context) {
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }
}

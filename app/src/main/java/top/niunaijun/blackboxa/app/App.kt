package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import top.niunaijun.blackboxa.view.sakuga.SakugaToast

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 21:21
 */
class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        mContext = base!!
        CrashLog.install()
        CrashLog.bread("App attachBaseContext")
        AppManager.doAttachBaseContext(base)

    }

    override fun onCreate() {
        super.onCreate()
        SakugaToast.register(this)
        CrashLog.bread("App onCreate start")
        CrashLog.startWatchdog()
        AppManager.doOnCreate(mContext)
        CrashLog.install()
        CrashLog.bread("App onCreate ok")
    }
}
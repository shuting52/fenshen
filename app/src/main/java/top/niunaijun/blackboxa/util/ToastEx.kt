package top.niunaijun.blackboxa.util

import android.content.Context
import androidx.annotation.StringRes
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.view.sakuga.SakugaToast

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/5/2 0:13
 */
fun Context.toast(msg:String){
    SakugaToast.show(this, msg)
}

fun toast(msg: String){
    App.getContext().toast(msg)
}

fun toast(@StringRes msgID:Int){
    toast(getString(msgID))
}
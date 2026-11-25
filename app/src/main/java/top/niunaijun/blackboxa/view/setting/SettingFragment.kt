package top.niunaijun.blackboxa.view.setting

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.chook.LibcHookActivity
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity
import top.niunaijun.blackboxa.view.sakuga.SakugaFX
import top.niunaijun.blackboxa.view.xp.XpActivity

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/5/6 22:13
 */
class SettingFragment : PreferenceFragmentCompat() {

    private lateinit var xpEnable: SwitchPreferenceCompat

    private lateinit var xpModule: Preference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 胶囊卡片之间不要分隔线
        setDivider(null)
        setDividerHeight(0)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        xpEnable = findPreference("xp_enable")!!
        xpEnable.isChecked = BlackBoxCore.get().isXPEnable


        xpEnable.setOnPreferenceChangeListener { _, newValue ->
            BlackBoxCore.get().isXPEnable = (newValue == true)
            sakugaToggle(xpEnable)
            true
        }
        //xp模块跳转
        xpModule = findPreference("xp_module")!!
        xpModule.setOnPreferenceClickListener {
            val intent = Intent(requireActivity(), XpActivity::class.java)
            requireContext().startActivity(intent)
            true
        }
        //按应用禁用 libc GOT hook（反作弊兼容）
        val libcHookPreference: Preference = findPreference("libc_hook_disable")!!
        libcHookPreference.setOnPreferenceClickListener {
            LibcHookActivity.start(requireContext())
            true
        }
        initGms()

        invalidHideState{
            val xpHidePreference: Preference = (findPreference("xp_hide")!!)
            val hideXposed = AppManager.mBlackBoxLoader.hideXposed()
            xpHidePreference.setDefaultValue(hideXposed)
            xpHidePreference
        }

        invalidHideState{
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)

        if (BlackBoxCore.get().isSupportGms) {

            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            when (preference.key) {
                "xp_hide" -> {
                    AppManager.mBlackBoxLoader.invalidHideXposed(tmpHide)
                }

                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }

                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
            }

            sakugaToggle(preference)
            toast(R.string.restart_module)
            return@setOnPreferenceChangeListener true
        }
    }

    /** 开关拨动：开关处溅起水花 + 卡一声（手绘 43 帧开关搬不过来，这是代码画的等价反应）。 */
    private fun sakugaToggle(preference: Preference) {
        val adapter = listView.adapter as? PreferenceGroupAdapter ?: return
        for (i in 0 until adapter.itemCount) {
            if (adapter.getItem(i) !== preference) continue
            val holder = listView.findViewHolderForAdapterPosition(i) ?: return
            val widgetFrame = holder.itemView.findViewById<ViewGroup>(android.R.id.widget_frame)
            val sw = widgetFrame?.getChildAt(0) ?: holder.itemView
            SakugaFX.splashAt(sw, sw.width / 2f, sw.height / 2f)
            SakugaFX.sfxAt(sw, SakugaFX.SFX_KACHI, sw.width / 2f, -10f, sizeSp = 18f)
            return
        }
    }
}

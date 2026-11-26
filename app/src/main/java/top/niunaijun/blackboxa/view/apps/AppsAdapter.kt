package top.niunaijun.blackboxa.view.apps

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.CrashLog
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.ItemAppBinding
import top.niunaijun.blackboxa.app.MuseUsage
import top.niunaijun.blackboxa.view.sakuga.SakugaFX

/**
 *
 * @Description: 软件显示界面适配器
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 21:52
 */

class AppsAdapter : RVHolderFactory() {

    private var firstBindLogged = false

    /** 批量删除：多选模式 + 已选中的分身（包名|用户 id） */
    var selectMode: Boolean = false
    val selected: MutableSet<String> = mutableSetOf()
    var onSelectionChanged: (() -> Unit)? = null

    /** 分身所在空间的 userId（分身列表页传进来），用来拼 Muse 账号关联的 key。 */
    var spaceId: Int = 0

    fun selectionKey(item: AppInfo): String = "${item.packageName}|${item.userId}"

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return AppsVH(inflate(R.layout.item_app,parent))
    }


    inner class AppsVH(itemView:View):RVHolder<AppInfo>(itemView){

        val binding = ItemAppBinding.bind(itemView)

        init {
            // 点按：压扁，松开爆裂 + 咚！
            SakugaFX.attachPress(itemView) { fx, x, y ->
                SakugaFX.burst(fx, x, y)
                SakugaFX.sfx(
                    fx, SakugaFX.SFX_DON, x,
                    y - 48f * itemView.resources.displayMetrics.density
                )
            }
        }

        override fun setContent(item: AppInfo, isSelected: Boolean, payload: Any?) {
            if (!firstBindLogged) {
                firstBindLogged = true
                CrashLog.bread("AppsAdapter first bind ${item.packageName}")
            }
            binding.icon.setImageDrawable(item.icon)
            binding.name.text = item.name
            if(item.isXpModule){
                binding.cornerLabel.visibility = View.VISIBLE
            }else{
                binding.cornerLabel.visibility = View.INVISIBLE
            }
            binding.check.visibility = if (selectMode) View.VISIBLE else View.GONE
            binding.check.setOnCheckedChangeListener(null)
            binding.check.isChecked = selected.contains(selectionKey(item))
            binding.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(selectionKey(item))
                else selected.remove(selectionKey(item))
                // 直接点勾选框：水晶碎片 + 唰
                if (checked) {
                    SakugaFX.shardsAt(binding.check, 14f, 14f)
                    SakugaFX.sfxAt(
                        binding.check, SakugaFX.SFX_ZUZA, 14f, -10f,
                        sizeSp = 18f, dotted = true
                    )
                }
                onSelectionChanged?.invoke()
            }
            // Muse 额度：关联了账号的分身，卡片下面显示剩的额度。
            val link = MuseUsage.getLink(itemView.context, spaceId, item.userId, item.packageName)
            val quota = link?.let { id ->
                MuseUsage.getCached(itemView.context)?.accounts?.find { it.id == id }
            }
            if (quota != null && quota.remainingPct >= 0) {
                binding.quotaBox.visibility = View.VISIBLE
                binding.quotaBar.progress = quota.remainingPct
                binding.quotaText.text = "剩 ${quota.remainingPct}% · ${quota.resetShort}重置"
            } else {
                binding.quotaBox.visibility = View.GONE
            }
        }

    }
}
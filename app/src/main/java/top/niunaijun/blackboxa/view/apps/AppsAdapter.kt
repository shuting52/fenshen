package top.niunaijun.blackboxa.view.apps

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.CrashLog
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.ItemAppBinding
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import top.niunaijun.blackboxa.app.MuseLoginDetect
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
            // 头像：优先显示登录账号的头像（从分身自己的数据里认出来的）；没有就用手动设的；再没有就显示应用图标。
            val autoBmp = if (MuseLoginDetect.isMuseClone(item.packageName, item.name))
                MuseLoginDetect.getAvatarBitmap(item.packageName, item.userId) else null
            val avatarLink = MuseUsage.getLink(itemView.context, spaceId, item.userId, item.packageName)
            val avatarBmp = autoBmp ?: avatarLink?.let { MuseUsage.getAvatarBitmap(itemView.context, it) }
            if (avatarBmp != null) {
                val d = RoundedBitmapDrawableFactory.create(itemView.resources, avatarBmp)
                d.isCircular = true
                binding.icon.setImageDrawable(d)
            }
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
            // Muse 额度：手动关联了就显示，关联是她自己认的；没关联的只靠自动认（邮箱对上了本身就是登录的证据）。
            // 2.3.35 那道“扫不到登录就不显示”的门拆了：Muse 不把邮箱明文留在 shared_prefs 里，扫描根本认不到登录，
            // 那道门只会把她关联好的额度也藏起来。没关联也没认上的分身本来就不会显示。
            val detectedEmail = MuseLoginDetect.getCached(item.packageName, item.userId)?.email
            val quota = MuseUsage.accountFor(itemView.context, spaceId, item.userId, item.packageName, detectedEmail)
            if (quota != null && quota.usedPct >= 0) {
                binding.quotaBox.visibility = View.VISIBLE
                binding.quotaBar.progress = quota.usedPct
                binding.quotaText.text = "已用 ${quota.usedPct}%"
                binding.quotaText2.text = "${quota.resetShort}重置"
                if (quota.extraLeft != null) {
                    binding.quotaText3.visibility = View.VISIBLE
                    binding.quotaText3.text = "额外 ${quota.extraLeft}"
                } else {
                    binding.quotaText3.visibility = View.GONE
                }
            } else {
                binding.quotaBox.visibility = View.GONE
            }
        }

    }
}
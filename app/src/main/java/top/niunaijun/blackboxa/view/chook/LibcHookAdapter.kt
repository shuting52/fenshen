package top.niunaijun.blackboxa.view.chook

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.LibcHookAppInfo
import top.niunaijun.blackboxa.databinding.ItemXpBinding
import top.niunaijun.blackboxa.view.sakuga.SakugaFX


class LibcHookAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return LibcHookVH(inflate(R.layout.item_xp, parent))
    }

    class LibcHookVH(itemView: View) : RVHolder<LibcHookAppInfo>(itemView) {

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

        private val binding = ItemXpBinding.bind(itemView)

        override fun setContent(item: LibcHookAppInfo, isSelected: Boolean, payload: Any?) {
            binding.icon.setImageDrawable(item.icon)
            binding.name.text = item.name
            binding.desc.text = item.packageName
            binding.enable.isChecked = item.disabled
            binding.enable.setOnCheckedChangeListener { buttonView, _ ->
                if (buttonView.isPressed) {
                    // 勾上：水晶碎片 + 唰
                    SakugaFX.shardsAt(buttonView, buttonView.width / 2f, buttonView.height / 2f)
                    SakugaFX.sfxAt(
                        buttonView, SakugaFX.SFX_ZUZA,
                        buttonView.width / 2f, -10f, sizeSp = 18f, dotted = true
                    )
                    binding.root.performClick()
                }
            }
        }
    }
}

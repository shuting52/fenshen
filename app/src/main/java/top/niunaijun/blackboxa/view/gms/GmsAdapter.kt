package top.niunaijun.blackboxa.view.gms

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.databinding.ItemGmsBinding
import top.niunaijun.blackboxa.view.sakuga.SakugaFX

/**
 *
 * @Description:
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:13
 */
class GmsAdapter : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return GmsVH(inflate(R.layout.item_gms,parent))
    }


    class GmsVH(itemView:View):RVHolder<GmsBean>(itemView){

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

        private val binding = ItemGmsBinding.bind(itemView)
        override fun setContent(item: GmsBean, isSelected: Boolean, payload: Any?) {
            binding.tvTitle.text = item.userName
            binding.checkbox.isChecked = item.isInstalledGms
            binding.checkbox.setOnCheckedChangeListener  { buttonView, _ ->
                if(buttonView.isPressed){
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
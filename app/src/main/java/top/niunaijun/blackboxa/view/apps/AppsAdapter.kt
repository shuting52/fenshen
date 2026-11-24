package top.niunaijun.blackboxa.view.apps

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.ItemAppBinding

/**
 *
 * @Description: 软件显示界面适配器
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 21:52
 */

class AppsAdapter : RVHolderFactory() {

    /** 批量删除：多选模式 + 已选中的分身（包名|用户 id） */
    var selectMode: Boolean = false
    val selected: MutableSet<String> = mutableSetOf()
    var onSelectionChanged: (() -> Unit)? = null

    fun selectionKey(item: AppInfo): String = "${item.packageName}|${item.userId}"

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return AppsVH(inflate(R.layout.item_app,parent))
    }


    inner class AppsVH(itemView:View):RVHolder<AppInfo>(itemView){

        val binding = ItemAppBinding.bind(itemView)

        override fun setContent(item: AppInfo, isSelected: Boolean, payload: Any?) {
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
                onSelectionChanged?.invoke()
            }
        }

    }
}
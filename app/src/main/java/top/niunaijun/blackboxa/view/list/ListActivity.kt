package top.niunaijun.blackboxa.view.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import eightbitlab.com.blurview.RenderScriptBlur
import com.ferfalk.simplesearchview.SimpleSearchView
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.databinding.ActivityListBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity
import top.niunaijun.blackboxa.view.glass.GlassWallpaper


class ListActivity : BaseActivity() {

    override val hasOwnGlassBackground: Boolean = true

    private val viewBinding: ActivityListBinding by inflate()

    private lateinit var mAdapter: RVAdapter<InstalledAppBean>

    private lateinit var viewModel: ListViewModel

    private var appList: List<InstalledAppBean> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.installed_app, true)
        Thread { GlassWallpaper.prepare(this) }.start()
        setupBlurView()

        mAdapter = RVAdapter<InstalledAppBean>(this,ListAdapter()).bind(viewBinding.recyclerView).setItemClickListener { _, item, _ ->
            showCloneCountDialog(item)
        }

        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)


        initSearchView()
        initViewModel()
    }

    /** 整屏磨砂玻璃：壁纸 + 强模糊 + boli 玻璃面 */
    private fun setupBlurView() {
        viewBinding.blurView.setupWith(viewBinding.root)
            .setBlurAlgorithm(RenderScriptBlur(this))
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)
            .setHasFixedTransformationMatrix(true)
        viewBinding.blurView.setOverlayColor(Color.parseColor("#66121212"))
    }

    /** 点一下应用：输入要创建的分身个数，一键批量分身（1-30 个） */
    private fun showCloneCountDialog(item: InstalledAppBean) {
        MaterialDialog(this).show {
            title(R.string.clone_count_title)
            input(
                hintRes = R.string.clone_count_hint,
                prefill = "1",
                inputType = InputType.TYPE_CLASS_NUMBER
            ) { _, input ->
                val count = input.toString().toIntOrNull()?.coerceIn(1, 30) ?: 1
                finishWithResult(item.packageName, count)
            }
            positiveButton(R.string.done)
            negativeButton(R.string.cancel)
        }
    }

    private fun initSearchView() {
        viewBinding.searchView.setOnQueryTextListener(object : SimpleSearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                filterApp(newText)
                return true
            }

            override fun onQueryTextCleared(): Boolean {
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                return true
            }

        })
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        val onlyShowXp = intent.getBooleanExtra("onlyShowXp", false)
        val userID = intent.getIntExtra("userID",0)

        if (onlyShowXp) {
            viewModel.getInstalledModules()
            viewBinding.toolbarLayout.toolbar.setTitle(R.string.installed_module)
        } else {
            viewModel.getInstallAppList(userID)
            viewBinding.toolbarLayout.toolbar.setTitle(R.string.installed_app)
        }

        viewModel.loadingLiveData.observe(this) {
            if (it) {
                viewBinding.stateView.showLoading()
            } else {
                viewBinding.stateView.showContent()

            }
        }

        viewModel.appsLiveData.observe(this) {
            if (it != null) {
                this.appList = it
                viewBinding.searchView.setQuery("", false)
                filterApp("")
                if (it.isNotEmpty()) {
                    viewBinding.stateView.showContent()
                    viewModel.previewInstalledList()
                } else {
                    viewBinding.stateView.showEmpty()
                }
            }
        }
    }

    private fun filterApp(newText: String) {
        val newList = this.appList.filter {
            it.name.contains(newText, true) or it.packageName.contains(newText, true)
        }
        mAdapter.setItems(newList)
    }

    private val openDocumentedResult = registerForActivityResult(ActivityResultContracts.GetContent()) {
        it?.run {
            finishWithResult(it.toString())
        }
    }

    private fun finishWithResult(source: String, count: Int = 1) {
        intent.putExtra("source", source)
        intent.putExtra("count", count)
        setResult(Activity.RESULT_OK, intent)
        val imm: InputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
        finish()
    }


    override fun onBackPressed() {
        if (viewBinding.searchView.isSearchOpen) {
            viewBinding.searchView.closeSearch()
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.list_choose) {
            openDocumentedResult.launch("application/vnd.android.package-archive")
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        val item = menu!!.findItem(R.id.list_search)
        viewBinding.searchView.setMenuItem(item)

        return true
    }

    override fun onStop() {
        super.onStop()
        viewModel.loadingLiveData.postValue(true)
        viewModel.loadingLiveData.removeObservers(this)
        viewModel.appsLiveData.postValue(null)
        viewModel.appsLiveData.removeObservers(this)
    }


    companion object{
        fun start(context: Context,onlyShowXp:Boolean){
            val intent = Intent(context,ListActivity::class.java)
            intent.putExtra("onlyShowXp",onlyShowXp)
            context.startActivity(intent)
        }
    }
}
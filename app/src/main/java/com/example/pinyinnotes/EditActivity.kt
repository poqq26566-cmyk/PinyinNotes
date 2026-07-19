package com.example.pinyinnotes

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar   // ✅ 新增
import android.widget.Toast
import android.view.View             // ✅ 新增
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri
    private lateinit var editText: EditText
    private lateinit var btnToggleMode: ImageButton
    private lateinit var btnChooseApp: ImageButton
    private var isReadMode = false

    // ✅ 修复1：应用列表全局缓存，整个App生命周期只加载一次图标
    companion object {
        @Volatile
        private var cachedApps: List<AppEntry>? = null
    }

    private val protectLinkFilter = InputFilter { _, _, _, dest, dstart, dend ->
        if (dest !is Spanned) return@InputFilter null
        val spans = dest.getSpans(0, dest.length, URLSpan::class.java)
        for (span in spans) {
            val spanStart = dest.getSpanStart(span)
            val spanEnd = dest.getSpanEnd(span)
            if (dstart < spanEnd && dend > spanStart) {
                return@InputFilter dest.subSequence(dstart, dend)
            }
        }
        null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        noteUri = Uri.parse(intent.getStringExtra("note_uri"))

        editText = findViewById(R.id.editContent)
        btnToggleMode = findViewById(R.id.btnToggleMode)
        btnChooseApp = findViewById(R.id.btnChooseApp)

        // ✅ 修复2：内容读取（含解密）移到子线程，避免卡住主线程
        Thread {
            val content = DocStore.getContent(this, noteUri)
            runOnUiThread {
                editText.setText(content)
                editText.requestFocus()
            }
        }.start()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                DocStore.setContent(this@EditActivity, noteUri, s.toString())
                if (isReadMode) {
                    Linkify.addLinks(editText, Linkify.WEB_URLS)
                }
            }
        })

        btnToggleMode.setOnClickListener {
            isReadMode = !isReadMode
            applyMode()
        }

        btnChooseApp.setOnClickListener { showAppPickerDialog() }

        applyMode()
    }

    private fun applyMode() {
        if (isReadMode) {
            Linkify.addLinks(editText, Linkify.WEB_URLS)
            editText.movementMethod = LinkMovementMethod.getInstance()
            editText.filters = arrayOf(protectLinkFilter)
            editText.setOnTouchListener(::handleLinkTouch)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            editText.filters = arrayOf()
            editText.movementMethod = ArrowKeyMovementMethod.getInstance()
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_edit)
        }
    }

    private fun handleLinkTouch(view: android.view.View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val et = view as EditText
            val layout = et.layout
            if (layout != null) {
                val y = event.y - et.totalPaddingTop + et.scrollY
                val x = event.x - et.totalPaddingLeft + et.scrollX
                if (y >= 0 && y <= layout.height) {
                    val line = layout.getLineForVertical(y.toInt())
                    if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                        val offset = layout.getOffsetForHorizontal(line, x)
                        val spannable = et.text as? Spannable
                        val spans = spannable?.getSpans(offset, offset, URLSpan::class.java)
                        if (!spans.isNullOrEmpty()) {
                            openLink(spans[0].url)
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun openLink(url: String) {
        val preferredPackage = LinkAppPreference.get(this, noteUri)
        if (preferredPackage != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.setPackage(preferredPackage)
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                val launchIntent = packageManager.getLaunchIntentForPackage(preferredPackage)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
            }
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showAppPickerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val editSearch = view.findViewById<EditText>(R.id.editSearchApp)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerApps)
        // ✅ 修复3：加载时显示进度条，给用户反馈
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarApps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择默认打开App")
            .setView(view)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除选择") { _, _ ->
                LinkAppPreference.clear(this, noteUri)
                Toast.makeText(this, "已恢复系统默认方式", Toast.LENGTH_SHORT).show()
            }
            .create()

        // ✅ 修复4：有缓存直接用，无需等待
        val cached = cachedApps
        if (cached != null) {
            progressBar?.visibility = View.GONE
            setupAppAdapter(cached, recyclerView, editSearch, dialog)
        } else {
            progressBar?.visibility = View.VISIBLE
            Thread {
                val apps = loadInstalledApps()
                cachedApps = apps  // 写入缓存
                runOnUiThread {
                    progressBar?.visibility = View.GONE
                    setupAppAdapter(apps, recyclerView, editSearch, dialog)
                }
            }.start()
        }

        dialog.show()
    }

    // ✅ 抽取公共方法，避免重复代码
    private fun setupAppAdapter(
        apps: List<AppEntry>,
        recyclerView: RecyclerView,
        editSearch: EditText,
        dialog: AlertDialog
    ) {
        val adapter = AppListAdapter(apps) { entry ->
            LinkAppPreference.set(this, noteUri, entry.packageName)
            Toast.makeText(this, "已设为默认：${entry.label}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        recyclerView.adapter = adapter
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(mainIntent, 0)

        return resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            // ✅ 修复5：先排序（只用文字，很快），再加载图标（只加载最终去重列表的图标）
            .sortedWith(compareBy(
                { PinyinUtils.getFirstLetter(it.loadLabel(packageManager).toString()) },
                { it.loadLabel(packageManager).toString() }
            ))
            .map { info ->
                AppEntry(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                    icon = info.loadIcon(packageManager)  // 图标只加载一次
                )
            }
    }
}

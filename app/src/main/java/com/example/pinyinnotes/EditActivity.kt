package com.example.pinyinnotes

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.regex.Pattern

class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri
    private lateinit var editText: EditText
    private lateinit var btnToggleMode: ImageButton
    private lateinit var btnChooseApp: ImageButton
    // ✅ 新增：阅读模式下显示渲染结果的 TextView
    private lateinit var tvReadView: TextView
    private var isReadMode = false

    companion object {
        @Volatile
        private var cachedApps: List<AppEntry>? = null

        // ✅ Markdown 超链接正则：匹配 [文字](URL)
        private val MD_LINK_PATTERN: Pattern =
            Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)")
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
        // ✅ 新增：获取阅读视图
        tvReadView = findViewById(R.id.tvReadView)

        var isLoadingContent = true
        Thread {
            val content = DocStore.getContent(this, noteUri)
            runOnUiThread {
                editText.setText(content)
                isLoadingContent = false
                editText.requestFocus()
            }
        }.start()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isLoadingContent) return
                DocStore.setContent(this@EditActivity, noteUri, s.toString())
                if (isReadMode) {
                    // ✅ 切换到渲染视图更新
                    renderMarkdownLinks(s.toString())
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
            // ✅ 阅读模式：隐藏编辑框，显示渲染 TextView
            editText.visibility = View.GONE
            tvReadView.visibility = View.VISIBLE
            renderMarkdownLinks(editText.text.toString())
            tvReadView.movementMethod = LinkMovementMethod.getInstance()
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            // ✅ 编辑模式：显示编辑框，隐藏渲染 TextView
            tvReadView.visibility = View.GONE
            editText.visibility = View.VISIBLE
            editText.filters = arrayOf()
            editText.movementMethod = ArrowKeyMovementMethod.getInstance()
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_edit)
        }
    }

    /**
     * ✅ 核心：解析 Markdown [文字](URL) 超链接语法，渲染为可点击 Span
     * 同时保留普通 http/https 裸链接的自动识别（Linkify）
     */
    private fun renderMarkdownLinks(text: String) {
        val builder = SpannableStringBuilder()
        val matcher = MD_LINK_PATTERN.matcher(text)
        var lastEnd = 0

        while (matcher.find()) {
            // 追加匹配前的普通文本
            val before = text.substring(lastEnd, matcher.start())
            builder.append(before)

            val displayText = matcher.group(1) ?: ""
            val url = matcher.group(2) ?: ""

            val spanStart = builder.length
            builder.append(displayText)
            val spanEnd = builder.length

            // 设置可点击 Span（蓝色 + 下划线）
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openLink(url)
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = 0xFF1565C0.toInt()   // 蓝色
                    ds.isUnderlineText = true
                    ds.bgColor = 0x00000000          // 点击时无背景色
                }
            }
            builder.setSpan(clickableSpan, spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // 同时加一层 URLSpan 方便长按复制链接（系统行为）
            builder.setSpan(URLSpan(url), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            lastEnd = matcher.end()
        }

        // 追加剩余文本
        builder.append(text.substring(lastEnd))

        // ✅ 对剩余的裸 URL（非 Markdown 格式）也自动 Linkify
        tvReadView.text = builder
        Linkify.addLinks(tvReadView, Linkify.WEB_URLS)
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

        val cached = cachedApps
        if (cached != null) {
            progressBar?.visibility = View.GONE
            setupAppAdapter(cached, recyclerView, editSearch, dialog)
        } else {
            progressBar?.visibility = View.VISIBLE
            Thread {
                val apps = loadInstalledApps()
                cachedApps = apps
                runOnUiThread {
                    progressBar?.visibility = View.GONE
                    setupAppAdapter(apps, recyclerView, editSearch, dialog)
                }
            }.start()
        }

        dialog.show()
    }

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
            .sortedWith(compareBy(
                { PinyinUtils.getFirstLetter(it.loadLabel(packageManager).toString()) },
                { it.loadLabel(packageManager).toString() }
            ))
            .map { info ->
                AppEntry(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                    icon = info.loadIcon(packageManager)
                )
            }
    }
}

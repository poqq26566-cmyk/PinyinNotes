package com.example.pinyinnotes

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var scrollReadView: ScrollView
    private lateinit var tvReadView: TextView
    private var isReadMode = false

    companion object {
        @Volatile
        private var cachedApps: List<AppEntry>? = null

        // ✅ 修复1：正则改为 [^)]* 允许括号内为空，覆盖空URL情况
        private val MD_LINK_PATTERN: Pattern =
            Pattern.compile("\\[([^\\]]+)\\]\\s*\\(([^)]*)\\)")
    }

    private val protectLinkFilter = InputFilter { _, _, _, dest, dstart, dend ->
        if (dest !is Spanned) return@InputFilter null
        val spans = dest.getSpans(0, dest.length, URLSpan::class.java)
        for (span in spans) {
            val spanStart = dest.getSpanStart(span)
            val spanEnd   = dest.getSpanEnd(span)
            if (dstart < spanEnd && dend > spanStart) {
                return@InputFilter dest.subSequence(dstart, dend)
            }
        }
        null
    }

    // 键盘监听：打字时隐藏按钮，键盘收起时恢复
    private val keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
        val rootView = window.decorView.rootView
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight   = rootView.height
        val keyboardHeight = screenHeight - rect.bottom
        val keyboardVisible = keyboardHeight > screenHeight * 0.15

        if (keyboardVisible) {
            btnToggleMode.visibility = View.GONE
            btnChooseApp.visibility  = View.GONE
        } else {
            btnToggleMode.visibility = View.VISIBLE
            btnChooseApp.visibility  = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        noteUri        = Uri.parse(intent.getStringExtra("note_uri"))
        editText       = findViewById(R.id.editContent)
        btnToggleMode  = findViewById(R.id.btnToggleMode)
        btnChooseApp   = findViewById(R.id.btnChooseApp)
        scrollReadView = findViewById(R.id.scrollReadView)
        tvReadView     = findViewById(R.id.tvReadView)

        // 注册键盘监听
        window.decorView.rootView
            .viewTreeObserver
            .addOnGlobalLayoutListener(keyboardListener)

        // 子线程读取内容（含解密），完成后才允许 TextWatcher 触发保存
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
                // 阅读模式下实时刷新渲染
                if (isReadMode) renderMarkdownLinks(s.toString())
            }
        })

        btnToggleMode.setOnClickListener {
            isReadMode = !isReadMode
            applyMode()
        }

        btnChooseApp.setOnClickListener { showAppPickerDialog() }

        applyMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销监听，防止内存泄漏
        window.decorView.rootView
            .viewTreeObserver
            .removeOnGlobalLayoutListener(keyboardListener)
    }

    private fun applyMode() {
        if (isReadMode) {
            // 阅读模式：隐藏编辑框，显示 ScrollView
            editText.visibility       = View.GONE
            scrollReadView.visibility = View.VISIBLE
            tvReadView.movementMethod = LinkMovementMethod.getInstance()
            renderMarkdownLinks(editText.text.toString())
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            // 编辑模式：隐藏 ScrollView，显示编辑框
            scrollReadView.visibility = View.GONE
            editText.visibility       = View.VISIBLE
            editText.filters          = arrayOf()
            editText.movementMethod   = ArrowKeyMovementMethod.getInstance()
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_edit)
            editText.requestFocus()
        }
    }

    /**
     * 解析 Markdown [文字](URL) → 可点击蓝色下划线链接
     * ✅ 支持 ] 和 ( 之间有空格的情况，渲染时自动去除空格
     * ✅ 修复2：URL为空时只渲染文字，不加点击效果，避免崩溃
     * 剩余裸 URL 由 Linkify 自动处理
     */
    private fun renderMarkdownLinks(text: String) {
        val builder = SpannableStringBuilder()
        val matcher = MD_LINK_PATTERN.matcher(text)
        var lastEnd = 0

        while (matcher.find()) {
            // 追加匹配前的普通文本
            builder.append(text.substring(lastEnd, matcher.start()))

            val displayText = matcher.group(1) ?: ""
            val url         = (matcher.group(2) ?: "").trim() // URL 去除首尾空格

            // ✅ 修复2：URL为空时只追加文字，不设置点击效果
            if (url.isBlank()) {
                builder.append(displayText)
                lastEnd = matcher.end()
                continue
            }

            val spanStart = builder.length
            builder.append(displayText)
            val spanEnd = builder.length

            // 设置可点击 Span（蓝色 + 下划线）
            builder.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) { openLink(url) }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color           = 0xFF1565C0.toInt()
                        ds.isUnderlineText = true
                    }
                },
                spanStart, spanEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            lastEnd = matcher.end()
        }

        // 追加剩余文本
        builder.append(text.substring(lastEnd))

        tvReadView.text = builder
        // 对剩余裸 URL 也自动识别为可点击链接
        Linkify.addLinks(tvReadView, Linkify.WEB_URLS)
    }

    private fun openLink(url: String) {
        // ✅ 修复3：URL为空时直接返回，不尝试启动 Intent
        if (url.isBlank()) {
            Toast.makeText(this, "链接地址为空", Toast.LENGTH_SHORT).show()
            return
        }

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

        // ✅ 修复4：兜底 startActivity 加 try-catch，防止系统没有任何 App 能处理该链接
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有找到可以打开此链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppPickerDialog() {
        val view         = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val editSearch   = view.findViewById<EditText>(R.id.editSearchApp)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerApps)
        val progressBar  = view.findViewById<ProgressBar>(R.id.progressBarApps)
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
                    label       = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                    icon        = info.loadIcon(packageManager)
                )
            }
    }
}

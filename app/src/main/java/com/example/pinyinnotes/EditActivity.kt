package com.example.pinyinnotes

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.os.Handler
import android.os.Looper
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
    private var isLoadingContent = true

    // ✅ 保存改为防抖 + 子线程，避免每次按键都在主线程同步加密写盘
    private val saveHandler = Handler(Looper.getMainLooper())
    private var pendingSaveRunnable: Runnable? = null
    private val SAVE_DEBOUNCE_MS = 400L

    // ✅ SharedPreferences 记录模式偏好
    private val prefs by lazy { getSharedPreferences("edit_mode_prefs", MODE_PRIVATE) }

    companion object {
        @Volatile
        private var cachedApps: List<AppEntry>? = null

        // ✅ 支持中文括号 ［］（） 以及半角括号 []()
        // 匹配格式：［文字］（URL）或 [文字](URL)
        // 允许 ] 和 ( 之间有空格
        private val MD_LINK_PATTERN: Pattern =
            Pattern.compile("[\\[［]([^\\]］]+)[\\]］]\\s*[\\(（]([^)）]*)[\\)）]")
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

        // ✅ 检查密钥是否就绪
        val treeUri = getSharedPreferences("pinyin_notes_prefs", MODE_PRIVATE)
            .getString("tree_uri", null)?.let { Uri.parse(it) }
        if (!KeyManager.isReady() && (treeUri == null || !KeyManager.ensureReady(this, treeUri))) {
            Toast.makeText(this, "密钥未就绪，请重新解锁", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        // ✅ 读取上次保存的模式偏好（默认 false = 编辑模式）
        isReadMode = prefs.getBoolean(noteUri.toString(), false)

        // 注册键盘监听
        window.decorView.rootView
            .viewTreeObserver
            .addOnGlobalLayoutListener(keyboardListener)

        // 子线程读取内容（含解密），完成后才允许 TextWatcher 触发保存
        isLoadingContent = true
        Thread {
            try {
                val content = DocStore.getContent(this, noteUri)
                runOnUiThread {
                    editText.setText(content)
                    isLoadingContent = false
                    editText.requestFocus()
                    // ✅ 内容加载完成后应用模式
                    applyMode()
                }
            } catch (e: IllegalStateException) {
                runOnUiThread {
                    Toast.makeText(this, "读取失败：密钥未就绪，请重新解锁", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "读取笔记失败", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isLoadingContent) return
                scheduleSave(s.toString())
                // 阅读模式下实时刷新渲染
                if (isReadMode) renderMarkdownLinks(s.toString())
            }
        })

        btnToggleMode.setOnClickListener {
            isReadMode = !isReadMode
            // ✅ 保存模式偏好
            prefs.edit().putBoolean(noteUri.toString(), isReadMode).apply()
            applyMode()
        }

        btnChooseApp.setOnClickListener { showAppPickerDialog() }

        // ✅ 注意：applyMode() 移到内容加载完成后调用，避免加载完成前闪烁
    }

    override fun onPause() {
        super.onPause()
        // 离开页面前立刻落盘，不等防抖计时器，防止丢失最后几个字
        flushSave()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销监听，防止内存泄漏
        window.decorView.rootView
            .viewTreeObserver
            .removeOnGlobalLayoutListener(keyboardListener)
        pendingSaveRunnable?.let { saveHandler.removeCallbacks(it) }
    }

    /** 停止输入 SAVE_DEBOUNCE_MS 后，在子线程加密写盘一次 */
    private fun scheduleSave(content: String) {
        pendingSaveRunnable?.let { saveHandler.removeCallbacks(it) }
        val runnable = Runnable { writeToDisk(content) }
        pendingSaveRunnable = runnable
        saveHandler.postDelayed(runnable, SAVE_DEBOUNCE_MS)
    }

    /** 立刻取消防抖计时器并同步保存当前内容（用于退出页面时） */
    private fun flushSave() {
        pendingSaveRunnable?.let { saveHandler.removeCallbacks(it) }
        pendingSaveRunnable = null
        if (!isLoadingContent) {
            writeToDisk(editText.text.toString())
        }
    }

    // ✅ writeToDisk 包一层 try/catch，避免保存时崩溃
    private fun writeToDisk(content: String) {
        Thread {
            try {
                DocStore.setContent(this@EditActivity, noteUri, content)
            } catch (e: IllegalStateException) {
                runOnUiThread {
                    Toast.makeText(this@EditActivity, "保存失败：密钥未就绪，请重新解锁", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@EditActivity, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
     * 解析 Markdown 链接，支持：
     * - 半角： [文字](URL)
     * - 全角： ［文字］（URL）
     * - 混合： [文字］（URL) 等
     *
     * ✅ URL为空时只渲染文字，不加点击效果
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
            val url         = (matcher.group(2) ?: "").trim()

            // ✅ URL为空时只追加文字，不设置点击效果
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

    /**
     * ✅ 增强链接打开逻辑，支持多种兜底方案
     */
    private fun openLink(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "链接地址为空", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ 强制添加 http:// 前缀（如果没有的话），与 Linkify 行为一致
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "http://$finalUrl"
        }

        val preferredPackage = LinkAppPreference.get(this, noteUri)
        if (preferredPackage != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            intent.setPackage(preferredPackage)
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // 选的应用打不开，继续走兜底
            }
        }

        // ✅ 兜底方案1：用和 Linkify 一样的方式（添加 CATEGORY_BROWSABLE）
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // 继续尝试下一个方案
        }

        // ✅ 兜底方案2：不加 CATEGORY_BROWSABLE，直接打开
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)))
            return
        } catch (e: ActivityNotFoundException) {
            // 继续尝试下一个方案
        }

        // ✅ 兜底方案3：尝试用 Chrome 强制打开
        try {
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            chromeIntent.setPackage("com.android.chrome")
            startActivity(chromeIntent)
            return
        } catch (e: Exception) {
            // Chrome 没有，继续
        }

        // ✅ 兜底方案4：尝试用 Edge 强制打开
        try {
            val edgeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            edgeIntent.setPackage("com.microsoft.emmx")
            startActivity(edgeIntent)
            return
        } catch (e: Exception) {
            // Edge 没有，继续
        }

        // ✅ 全部失败，提示用户
        Toast.makeText(this, "没有找到可以打开此链接的应用", Toast.LENGTH_SHORT).show()
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

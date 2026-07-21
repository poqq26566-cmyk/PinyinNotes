package com.example.pinyinnotes

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

    // ✅ 键盘监听：键盘弹出时隐藏按钮，键盘收起时恢复
    private val keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
        val rootView = window.decorView.rootView
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height
        val keyboardHeight = screenHeight - rect.bottom

        // 键盘高度超过屏幕 15% 则认为键盘弹出
        val keyboardVisible = keyboardHeight > screenHeight * 0.15

        if (keyboardVisible) {
            // 打字中：隐藏两个按钮
            btnToggleMode.visibility = View.GONE
            btnChooseApp.visibility  = View.GONE
        } else {
            // 键盘收起：恢复显示按钮
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

        // ✅ 注册键盘监听
        window.decorView.rootView
            .viewTreeObserver
            .addOnGlobalLayoutListener(keyboardListener)

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
        // ✅ 退出时注销监听，防止内存泄漏
        window.decorView.rootView
            .viewTreeObserver
            .removeOnGlobalLayoutListener(keyboardListener)
    }

    private fun applyMode() {
        if (isReadMode) {
            editText.visibility       = View.GONE
            scrollReadView.visibility = View.VISIBLE
            tvReadView.movementMethod = LinkMovementMethod.getInstance()
            renderMarkdownLinks(editText.text.toString())
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            scrollReadView.visibility = View.GONE
            editText.visibility       = View.VISIBLE
            editText.filters          = arrayOf()
            editText.movementMethod   = ArrowKeyMovementMethod.getInstance()
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_edit)
            editText.requestFocus()
        }
    }

    private fun renderMarkdownLinks(text: String) {
        val builder = SpannableStringBuilder()
        val matcher = MD_LINK_PATTERN.matcher(text)
        var lastEnd = 0

        while (matcher.find()) {
            builder.append(text.substring(lastEnd, matcher.start()))

            val displayText = matcher.group(1) ?: ""
            val url         = matcher.group(2) ?: ""
            val spanStart   = builder.length
            builder.append(displayText)
            val spanEnd = builder.length

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

        builder.append(text.substring(lastEnd))
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

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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 全屏空白编辑页，内容自动加密保存。
 * 右下角按钮切换 编辑模式 / 阅读模式：
 * - 编辑模式：完全自由编辑，链接不高亮、点哪都只是定位光标
 * - 阅读模式：其他内容照样能改，也能新增链接，但已生成的链接文字本身
 *             不能被修改或删除；点击链接直接跳转打开
 * 另有"选择默认打开App"按钮：点击链接时用指定的App打开，
 * 每条笔记各自独立记住自己的选择，可随时重新选择更换。
 */
class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri
    private lateinit var editText: EditText
    private lateinit var btnToggleMode: ImageButton
    private lateinit var btnChooseApp: ImageButton
    private var isReadMode = false

    /** 阻止对已有链接文字的修改/删除，其余内容不受影响 */
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

        editText.setText(DocStore.getContent(this, noteUri))

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
        editText.requestFocus()
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

    /** 有记住的默认App就用它打开，没有就走系统默认方式 */
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

        Thread {
            val apps = loadInstalledApps()
            runOnUiThread {
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
        }.start()

        dialog.show()
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(mainIntent, 0)

        return resolveInfos
            .map { info ->
                AppEntry(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                    icon = info.loadIcon(packageManager)
                )
            }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.label) }, { it.label }))
    }
}

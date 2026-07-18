package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.method.TextKeyListener
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/**
 * 全屏空白编辑页，内容自动加密保存。
 * 右下角按钮切换 编辑模式 / 阅读模式：
 * - 编辑模式：完全自由编辑，链接不高亮、点哪都只是定位光标
 * - 阅读模式：其他内容照样能改，也能新增链接，但已生成的链接文字本身
 *             不能被修改或删除；点击链接直接跳转打开
 */
class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri
    private lateinit var editText: EditText
    private lateinit var btnToggleMode: ImageButton
    private var isReadMode = false

    /** 阻止对已有链接文字的修改/删除，其余内容不受影响 */
    private val protectLinkFilter = InputFilter { _, _, _, dest, dstart, dend ->
        if (dest !is Spanned) return@InputFilter null
        val spans = dest.getSpans(0, dest.length, URLSpan::class.java)
        for (span in spans) {
            val spanStart = dest.getSpanStart(span)
            val spanEnd = dest.getSpanEnd(span)
            if (dstart < spanEnd && dend > spanStart) {
                // 这次编辑碰到了已有链接的字符范围，原样放回去，等于不允许改
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

        editText.setText(DocStore.getContent(this, noteUri))

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                DocStore.setContent(this@EditActivity, noteUri, s.toString())
                if (isReadMode) {
                    // 识别新出现的链接，让它们也变成受保护、可点击的链接
                    Linkify.addLinks(editText, Linkify.WEB_URLS)
                }
            }
        })

        btnToggleMode.setOnClickListener {
            isReadMode = !isReadMode
            applyMode()
        }

        applyMode()
        editText.requestFocus()
    }

    private fun applyMode() {
        if (isReadMode) {
            Linkify.addLinks(editText, Linkify.WEB_URLS)
            editText.movementMethod = LinkMovementMethod.getInstance()
            editText.filters = arrayOf(protectLinkFilter)
            editText.setOnTouchListener(::handleLinkTouch)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_edit)
        } else {
            editText.filters = arrayOf()
            editText.movementMethod = ArrowKeyMovementMethod.getInstance()
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_view)
        }
    }

    /** 只有精准点在链接文字上才跳转打开，其他地方交给正常的编辑/光标定位逻辑 */
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
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spans[0].url)))
                            return true
                        }
                    }
                }
            }
        }
        return false
    }
}

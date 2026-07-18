package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/**
 * 全屏空白编辑页，内容自动加密保存。
 * 右下角按钮切换 编辑模式 / 阅读模式：
 * - 编辑模式：纯文本正常打字，不识别链接，点哪都是定位光标
 * - 阅读模式：不能打字，链接会加下划线且可点击直接打开
 */
class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri
    private lateinit var editText: EditText
    private lateinit var btnToggleMode: ImageButton
    private var isReadMode = false

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
            // 阅读模式：禁止编辑，识别链接并可点击
            editText.keyListener = null
            editText.isCursorVisible = false
            Linkify.addLinks(editText, Linkify.WEB_URLS)
            editText.movementMethod = LinkMovementMethod.getInstance()
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_edit)
        } else {
            // 编辑模式：正常打字，不响应链接点击
            editText.keyListener = android.text.method.TextKeyListener.getInstance()
            editText.isCursorVisible = true
            editText.movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
            val spannable = editText.text as? Spannable
            spannable?.getSpans(0, spannable.length, URLSpan::class.java)?.forEach {
                spannable.removeSpan(it)
            }
            editText.setOnTouchListener(null)
            btnToggleMode.setImageResource(android.R.drawable.ic_menu_view)
        }
    }
}

package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** 全屏空白编辑页，内容自动加密保存；文字里的链接会自动识别并可点击打开 */
class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        noteUri = Uri.parse(intent.getStringExtra("note_uri"))

        val editText: EditText = findViewById(R.id.editContent)
        editText.setText(DocStore.getContent(this, noteUri))
        Linkify.addLinks(editText, Linkify.WEB_URLS)

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                DocStore.setContent(this@EditActivity, noteUri, s.toString())
                // 重新识别链接（内容变了要刷新一次）
                Linkify.addLinks(editText, Linkify.WEB_URLS)
            }
        })

        // 只有精准点在链接文字上才跳转打开，空白区域/其他文字照常编辑
        editText.setOnTouchListener { view, event ->
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
                                val url = spans[0].url
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                return@setOnTouchListener true
                            }
                        }
                    }
                }
            }
            false
        }

        editText.requestFocus()
    }
}

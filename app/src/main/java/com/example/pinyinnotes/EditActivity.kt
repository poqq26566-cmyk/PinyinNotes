package com.example.pinyinnotes

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** 全屏空白编辑页，内容自动加密保存 */
class EditActivity : AppCompatActivity() {

    private lateinit var noteUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        noteUri = Uri.parse(intent.getStringExtra("note_uri"))

        val editText: EditText = findViewById(R.id.editContent)
        editText.setText(DocStore.getContent(this, noteUri))

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                DocStore.setContent(this@EditActivity, noteUri, s.toString())
            }
        })

        editText.requestFocus()
    }
}

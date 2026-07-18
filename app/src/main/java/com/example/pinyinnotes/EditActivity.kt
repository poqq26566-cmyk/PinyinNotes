package com.example.pinyinnotes

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** 全屏空白编辑页，内容自动加密保存到对应文件 */
class EditActivity : AppCompatActivity() {

    private var repository: NoteRepository? = null
    private lateinit var noteUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        noteUri = Uri.parse(intent.getStringExtra("note_uri"))

        val prefs = getSharedPreferences("pinyin_notes_prefs", MODE_PRIVATE)
        val savedTreeUri = prefs.getString("tree_uri", null)
        val editText: EditText = findViewById(R.id.editContent)

        if (savedTreeUri != null) {
            repository = NoteRepository(this, Uri.parse(savedTreeUri))
            editText.setText(repository?.getNoteContent(noteUri) ?: "")
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                repository?.updateNoteContent(noteUri, s.toString())
            }
        })

        editText.requestFocus()
    }
}

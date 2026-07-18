package com.example.pinyinnotes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** 全屏空白编辑页，点击列表某一项后进入，内容实时自动保存到对应的 txt 文件 */
class EditActivity : AppCompatActivity() {

    private lateinit var repository: NoteRepository
    private lateinit var noteName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        repository = NoteRepository(this)
        noteName = intent.getStringExtra("note_name") ?: ""

        val editText: EditText = findViewById(R.id.editContent)
        editText.setText(repository.getNoteContent(noteName))

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                repository.updateNoteContent(noteName, s.toString())
            }
        })

        editText.requestFocus()
    }
}

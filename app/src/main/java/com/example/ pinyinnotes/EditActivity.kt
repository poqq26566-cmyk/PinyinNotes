package com.example.pinyinnotes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** 全屏空白编辑页，点击列表某一项后进入，内容实时自动保存 */
class EditActivity : AppCompatActivity() {

    private lateinit var repository: NoteRepository
    private var noteId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        repository = NoteRepository(this)
        noteId = intent.getLongExtra("note_id", -1)

        val editText: EditText = findViewById(R.id.editContent)
        val note = repository.getNoteById(noteId)
        editText.setText(note?.content ?: "")

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                repository.updateNoteContent(noteId, s.toString())
            }
        })

        editText.requestFocus()
    }
}

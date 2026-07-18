package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 分类内页：显示该分类下的笔记，按拼音 A-Z 分组 */
class CategoryActivity : AppCompatActivity() {

    private var repository: NoteRepository? = null
    private lateinit var adapter: NoteAdapter<Note>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            val categoryUri = Uri.parse(intent.getStringExtra("category_uri"))
            val categoryDoc = DocumentFile.fromTreeUri(this, categoryUri)
                ?: throw IllegalStateException("无法访问该分类")
            repository = NoteRepository(this, categoryDoc)

            val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
            recyclerView.layoutManager = LinearLayoutManager(this)
            adapter = NoteAdapter(
                onClick = { note -> openEdit(note) },
                onLongClick = { note -> confirmDelete(note) }
            )
            recyclerView.adapter = adapter

            val fab: ImageButton = findViewById(R.id.fab)
            fab.setOnClickListener { showAddDialog() }
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("出错了")
                .setMessage(e.toString() + "\n\n" + e.stackTraceToString().take(1000))
                .setPositiveButton("确定") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private var notes = mutableListOf<Note>()

    private fun refreshList() {
        val repo = repository ?: return
        Thread {
            val list = repo.getAllNotes()
            runOnUiThread {
                notes = list.toMutableList()
                adapter.submitEntries(notes)
            }
        }.start()
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_note, null)
        val editText = view.findViewById<EditText>(R.id.editName)
        AlertDialog.Builder(this)
            .setTitle("新建名称")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val repo = repository

                    val tempNote = Note(name, Uri.EMPTY)
                    notes.add(tempNote)
                    notes.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                    adapter.submitEntries(notes)

                    Thread {
                        val realNote = repo?.addNote(name)
                        runOnUiThread {
                            val idx = notes.indexOfFirst { it === tempNote }
                            if (idx >= 0) {
                                if (realNote != null) {
                                    notes[idx] = realNote
                                } else {
                                    notes.removeAt(idx)
                                }
                                adapter.submitEntries(notes)
                            }
                        }
                    }.start()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("删除\u201c${note.name}\u201d")
            .setMessage("删除后无法恢复，确定吗？")
            .setPositiveButton("删除") { _, _ ->
                notes.removeAll { it.uri == note.uri }
                adapter.submitEntries(notes)

                val repo = repository
                Thread {
                    repo?.deleteNote(note.uri)
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openEdit(note: Note) {
        if (note.uri == Uri.EMPTY) {
            android.widget.Toast.makeText(this, "还在创建中，请稍等", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditActivity::class.java)
        intent.putExtra("note_uri", note.uri.toString())
        startActivity(intent)
    }
}

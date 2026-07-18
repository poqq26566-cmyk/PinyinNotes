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

    private lateinit var repository: NoteRepository
    private lateinit var adapter: NoteAdapter<Note>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val categoryUri = Uri.parse(intent.getStringExtra("category_uri"))
        val categoryDoc = DocumentFile.fromSingleUri(this, categoryUri)
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
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.submitEntries(repository.getAllNotes())
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
                    repository.addNote(name)
                    refreshList()
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
                repository.deleteNote(note.uri)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openEdit(note: Note) {
        val intent = Intent(this, EditActivity::class.java)
        intent.putExtra("note_uri", note.uri.toString())
        startActivity(intent)
    }
}

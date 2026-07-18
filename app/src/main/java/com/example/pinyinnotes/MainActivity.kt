package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private var repository: NoteRepository? = null
    private lateinit var adapter: NoteAdapter

    private val prefs by lazy { getSharedPreferences("pinyin_notes_prefs", MODE_PRIVATE) }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            repository = NoteRepository(this, uri)
            refreshList()
        } else {
            Toast.makeText(this, "需要选择一个文件夹才能使用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = NoteAdapter(
            onClick = { note -> openEdit(note) },
            onLongClick = { note -> confirmDelete(note) }
        )
        recyclerView.adapter = adapter

        val fab: ImageButton = findViewById(R.id.fab)
        fab.setOnClickListener {
            if (repository == null) pickFolder() else showAddDialog()
        }

        val savedUri = prefs.getString("tree_uri", null)
        if (savedUri != null) {
            try {
                repository = NoteRepository(this, Uri.parse(savedUri))
            } catch (e: Exception) {
                repository = null
            }
        }
        if (repository == null) {
            pickFolder()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        Toast.makeText(this, "请选择/新建 Vault/拼音笔记 文件夹", Toast.LENGTH_LONG).show()
        folderPicker.launch(intent)
    }

    private fun refreshList() {
        repository?.let { adapter.submitNotes(it.getAllNotes()) }
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
                    repository?.addNote(name)
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
                repository?.deleteNote(note.uri)
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

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
    private var categoryUri: Uri = Uri.EMPTY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            categoryUri = Uri.parse(intent.getStringExtra("category_uri"))
            val categoryDoc = DocumentFile.fromTreeUri(this, categoryUri)
                ?: throw IllegalStateException("无法访问该分类")
            repository = NoteRepository(this, categoryDoc)

            val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
            recyclerView.layoutManager = LinearLayoutManager(this)
            adapter = NoteAdapter(
                onClick = { note -> openEdit(note) },
                onLongClick = { note -> showNoteOptions(note) }
            )
            recyclerView.adapter = adapter

            // 有缓存就先秒开显示，后台再刷新真实数据
            NotesCache.get(categoryUri)?.let {
                notes = it.toMutableList()
                adapter.submitEntries(notes)
            }

            val fab: ImageButton = findViewById(R.id.fab)
            fab.setOnClickListener { showAddDialog() }

            val btnCheckDuplicate: android.widget.Button = findViewById(R.id.btnCheckDuplicate)
            btnCheckDuplicate.setOnClickListener { checkDuplicates() }
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
            NotesCache.put(categoryUri, list)
            runOnUiThread {
                notes = list.toMutableList()
                adapter.submitEntries(notes)
            }
        }.start()
    }

    private fun showRenameNoteDialog(note: Note) {
    val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_note, null)
    val editText = view.findViewById<EditText>(R.id.editName)
    editText.setText(note.name)
    AlertDialog.Builder(this)
        .setTitle("重命名")
        .setView(view)
        .setPositiveButton("确定") { _, _ ->
            val newName = editText.text.toString().trim()
            if (newName.isNotEmpty() && newName != note.name) {
                val repo = repository

                // ✅ 第一步：立即乐观更新 UI，先用旧 uri 占位，名字换成新名字
                val idx = notes.indexOfFirst { it.uri == note.uri }
                if (idx >= 0) {
                    notes[idx] = Note(newName, note.uri) // 占位，uri 暂时不变
                    notes.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                    adapter.submitEntries(notes)
                }

                // ✅ 第二步：后台执行真正的重命名
                Thread {
                    val renamed = repo?.renameNote(note.uri, newName)
                    runOnUiThread {
                        if (renamed != null) {
                            // 成功：用真实的新 uri 替换占位条目
                            val newIdx = notes.indexOfFirst { it.uri == note.uri }
                            if (newIdx >= 0) {
                                notes[newIdx] = renamed
                                adapter.submitEntries(notes)
                            }
                        } else {
                            // 失败：回滚 UI，还原旧名字
                            val newIdx = notes.indexOfFirst { it.uri == note.uri }
                            if (newIdx >= 0) {
                                notes[newIdx] = note
                            } else {
                                notes.add(note)
                            }
                            notes.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                            adapter.submitEntries(notes)
                            android.widget.Toast.makeText(this, "重命名失败", android.widget.Toast.LENGTH_SHORT).show()
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

    private fun showNoteOptions(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(note.name)
            .setItems(arrayOf("重命名", "复制名称", "删除")) { _, which ->
                when (which) {
                    0 -> showRenameNoteDialog(note)
                    1 -> copyNoteName(note)
                    2 -> confirmDelete(note)
                }
            }
            .show()
    }

    // ✅ 修复：直接复制笔记名称，不读取文件内容
    private fun copyNoteName(note: Note) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("note_name", note.name)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(this, "已复制：${note.name}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showRenameNoteDialog(note: Note) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_note, null)
        val editText = view.findViewById<EditText>(R.id.editName)
        editText.setText(note.name)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != note.name) {
                    val repo = repository
                    Thread {
                        val renamed = repo?.renameNote(note.uri, newName)
                        runOnUiThread {
                            if (renamed != null) {
                                val idx = notes.indexOfFirst { it.uri == note.uri }
                                if (idx >= 0) {
                                    notes[idx] = renamed
                                    notes.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                                    adapter.submitEntries(notes)
                                }
                            } else {
                                android.widget.Toast.makeText(this, "重命名失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkDuplicates() {
        val dups = notes.groupBy { it.name }.filter { it.value.size > 1 }
        val result = if (dups.isEmpty()) {
            "没有发现重复名称"
        } else {
            buildString {
                append("重复的笔记名称：\n")
                dups.keys.forEach { append("• $it\n") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("重复名称检测结果")
            .setMessage(result)
            .setPositiveButton("确定", null)
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

package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
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
                onLongClick = { note -> showNoteOptions(note) },
                getWordCount = { note -> getNoteWordCount(note) }
            )
            recyclerView.adapter = adapter

            val letterIndexBar: android.widget.LinearLayout = findViewById(R.id.letterIndexBar)
            LetterIndexBarHelper.setup(letterIndexBar, recyclerView) { letter ->
                adapter.getPositionForLetter(letter)
            }

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

    private fun getNoteWordCount(note: Note): Int {
        return try {
            val content = DocStore.getContent(this, note.uri)
            content.replace(Regex("\\s+"), "").length
        } catch (e: IllegalStateException) {
            // 密钥未就绪，返回 0 并提示
            runOnUiThread {
                Toast.makeText(this, "密钥未就绪，请返回重新解锁", Toast.LENGTH_SHORT).show()
            }
            0
        } catch (e: Exception) {
            0
        }
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

    private fun copyNoteName(note: Note) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("note_name", note.name)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制：${note.name}", Toast.LENGTH_SHORT).show()
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
                    val idx = notes.indexOfFirst { it.uri == note.uri }
                    if (idx >= 0) {
                        val oldNote = notes[idx]
                        val tempNote = Note(newName, Uri.EMPTY)
                        notes[idx] = tempNote
                        notes.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                        adapter.submitEntries(notes)

                        Thread {
                            val renamed = repo?.renameNote(oldNote.uri, newName)
                            runOnUiThread {
                                val curIdx = notes.indexOfFirst { it === tempNote }
                                if (renamed != null) {
                                    if (curIdx >= 0) notes[curIdx] = renamed
                                } else {
                                    if (curIdx >= 0) notes[curIdx] = oldNote
                                    Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
                                }
                                notes.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                                adapter.submitEntries(notes)
                            }
                        }.start()
                    }
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
            Toast.makeText(this, "正在处理，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditActivity::class.java)
        intent.putExtra("note_uri", note.uri.toString())
        startActivity(intent)
    }
}

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

/** 首页：分类列表，点进分类才能看到里面的笔记 */
class MainActivity : AppCompatActivity() {

    private var categoryRepository: CategoryRepository? = null
    private lateinit var adapter: NoteAdapter<Category>

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
            // 先在子线程初始化密钥，成功后再创建 repository
            Thread {
                val ok = KeyManager.loadOrCreate(this, uri)
                runOnUiThread {
                    if (ok) {
                        prefs.edit().putString("tree_uri", uri.toString()).apply()
                        try {
                            categoryRepository = CategoryRepository(this, uri)
                            refreshList()
                        } catch (e: Exception) {
                            Toast.makeText(this, "文件夹初始化失败，请重试", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, "密钥初始化失败，请重新选择文件夹", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
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
            onClick = { category -> openCategory(category) },
            onLongClick = { category -> showCategoryOptions(category) }
        )
        recyclerView.adapter = adapter

        val letterIndexBar: android.widget.LinearLayout = findViewById(R.id.letterIndexBar)
        LetterIndexBarHelper.setup(letterIndexBar, recyclerView) { letter ->
            adapter.getPositionForLetter(letter)
        }

        val fab: ImageButton = findViewById(R.id.fab)
        fab.setOnClickListener {
            if (categoryRepository == null) pickFolder() else showAddCategoryDialog()
        }

        val btnCheckDuplicate: android.widget.Button = findViewById(R.id.btnCheckDuplicate)
        btnCheckDuplicate.setOnClickListener { checkDuplicates() }

        // 启动时读取上次保存的文件夹，先加载密钥再初始化 repository
        val savedUri = prefs.getString("tree_uri", null)
        if (savedUri != null) {
            Thread {
                val uri = Uri.parse(savedUri)
                val ok = KeyManager.loadOrCreate(this, uri)
                runOnUiThread {
                    if (ok) {
                        try {
                            categoryRepository = CategoryRepository(this, uri)
                            refreshList()
                        } catch (e: Exception) {
                            categoryRepository = null
                            pickFolder()
                        }
                    } else {
                        // 密钥文件读取失败（可能权限失效），重新选文件夹
                        pickFolder()
                    }
                }
            }.start()
        } else {
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

    private var categories = mutableListOf<Category>()

    private fun refreshList() {
        val repo = categoryRepository ?: return
        Thread {
            val list = repo.getAllCategories()
            runOnUiThread {
                categories = list.toMutableList()
                adapter.submitEntries(categories)
            }
        }.start()
    }

    private fun showAddCategoryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_note, null)
        val editText = view.findViewById<EditText>(R.id.editName)
        AlertDialog.Builder(this)
            .setTitle("新建分类")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val repo = categoryRepository

                    // 乐观更新：立刻显示在列表里，不等磁盘真正写完
                    val tempCategory = Category(name, Uri.EMPTY)
                    categories.add(tempCategory)
                    categories.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                    adapter.submitEntries(categories)

                    Thread {
                        val realCategory = repo?.addCategory(name)
                        runOnUiThread {
                            val idx = categories.indexOfFirst { it === tempCategory }
                            if (idx >= 0) {
                                if (realCategory != null) {
                                    categories[idx] = realCategory
                                } else {
                                    categories.removeAt(idx)
                                }
                                adapter.submitEntries(categories)
                            }
                        }
                    }.start()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteCategory(category: Category) {
        AlertDialog.Builder(this)
            .setTitle("删除分类\u201c${category.name}\u201d")
            .setMessage("分类里的笔记会一起删除，确定吗？")
            .setPositiveButton("删除") { _, _ ->
                // 乐观更新：立刻从列表移除
                categories.removeAll { it.uri == category.uri }
                adapter.submitEntries(categories)

                Thread {
                    DocStore.delete(this, category.uri)
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCategoryOptions(category: Category) {
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(arrayOf("重命名", "删除")) { _, which ->
                when (which) {
                    0 -> showRenameCategoryDialog(category)
                    1 -> confirmDeleteCategory(category)
                }
            }
            .show()
    }

    private fun showRenameCategoryDialog(category: Category) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_note, null)
        val editText = view.findViewById<EditText>(R.id.editName)
        editText.setText(category.name)
        AlertDialog.Builder(this)
            .setTitle("重命名分类")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != category.name) {
                    val repo = categoryRepository
                    val idx = categories.indexOfFirst { it.uri == category.uri }
                    if (idx >= 0) {
                        val oldCategory = categories[idx]
                        val tempCategory = Category(newName, Uri.EMPTY)
                        categories[idx] = tempCategory
                        categories.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                        adapter.submitEntries(categories)

                        Thread {
                            val renamed = repo?.renameCategory(this, oldCategory.uri, newName)
                            runOnUiThread {
                                val curIdx = categories.indexOfFirst { it === tempCategory }
                                if (renamed != null) {
                                    if (curIdx >= 0) categories[curIdx] = renamed
                                } else {
                                    if (curIdx >= 0) categories[curIdx] = oldCategory
                                    Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show()
                                }
                                categories.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                                adapter.submitEntries(categories)
                            }
                        }.start()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkDuplicates() {
        Toast.makeText(this, "检测中...", Toast.LENGTH_SHORT).show()
        val snapshot = categories.toList()
        Thread {
            val report = StringBuilder()

            val dupCats = snapshot.groupBy { it.name }.filter { it.value.size > 1 }
            if (dupCats.isNotEmpty()) {
                report.append("重复的分类名称：\n")
                dupCats.keys.forEach { report.append("• $it\n") }
                report.append("\n")
            }

            for (category in snapshot) {
                try {
                    val folderDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, category.uri)
                        ?: continue
                    val noteRepo = NoteRepository(this, folderDoc)
                    val dupNotes = noteRepo.getAllNotes().groupBy { it.name }.filter { it.value.size > 1 }
                    if (dupNotes.isNotEmpty()) {
                        report.append("分类\u201c${category.name}\u201d里重复的笔记名称：\n")
                        dupNotes.keys.forEach { report.append("• $it\n") }
                        report.append("\n")
                    }
                } catch (e: Exception) {
                    // 跳过读取失败的分类
                }
            }

            val result = if (report.isEmpty()) "没有发现重复名称" else report.toString()
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("重复名称检测结果")
                    .setMessage(result)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }.start()
    }

    private fun openCategory(category: Category) {
        if (category.uri == Uri.EMPTY) {
            Toast.makeText(this, "正在处理，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CategoryActivity::class.java)
        intent.putExtra("category_uri", category.uri.toString())
        startActivity(intent)
    }
}

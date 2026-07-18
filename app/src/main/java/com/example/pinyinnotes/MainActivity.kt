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
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            categoryRepository = CategoryRepository(this, uri)
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
            onClick = { category -> openCategory(category) },
            onLongClick = { category -> confirmDeleteCategory(category) }
        )
        recyclerView.adapter = adapter

        val fab: ImageButton = findViewById(R.id.fab)
        fab.setOnClickListener {
            if (categoryRepository == null) pickFolder() else showAddCategoryDialog()
        }

        val savedUri = prefs.getString("tree_uri", null)
        if (savedUri != null) {
            try {
                categoryRepository = CategoryRepository(this, Uri.parse(savedUri))
            } catch (e: Exception) {
                categoryRepository = null
            }
        }
        if (categoryRepository == null) {
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

    private fun openCategory(category: Category) {
        if (category.uri == Uri.EMPTY) {
            Toast.makeText(this, "还在创建中，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CategoryActivity::class.java)
        intent.putExtra("category_uri", category.uri.toString())
        startActivity(intent)
    }
}

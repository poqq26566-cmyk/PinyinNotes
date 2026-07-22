package com.example.pinyinnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 首页：分类列表，点进分类才能看到里面的笔记 */
class MainActivity : AppCompatActivity() {

    private var categoryRepository: CategoryRepository? = null
    private lateinit var adapter: NoteAdapter<Category>

    private val prefs by lazy { getSharedPreferences("pinyin_notes_prefs", MODE_PRIVATE) }
    private var passwordDialog: AlertDialog? = null  // ✅ 保存密码弹窗引用

    private val handler = Handler(Looper.getMainLooper())

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ensureVaultUnlocked(
                uri = uri,
                onReady = {
                    prefs.edit().putString("tree_uri", uri.toString()).apply()
                    try {
                        categoryRepository = CategoryRepository(this, uri)
                        refreshList()
                    } catch (e: Exception) {
                        Toast.makeText(this, "文件夹初始化失败，请重试", Toast.LENGTH_LONG).show()
                    }
                },
                onCancelled = {
                    Toast.makeText(this, "需要密码才能使用该文件夹", Toast.LENGTH_LONG).show()
                }
            )
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

        // 启动时读取上次保存的文件夹：优先用本机免密缓存直接解锁，
        // 没有缓存（比如刚装完 App 第一次配合旧文件夹，或缓存被清）才弹密码框
        val savedUri = prefs.getString("tree_uri", null)
        if (savedUri != null) {
            val uri = Uri.parse(savedUri)
            ensureVaultUnlocked(
                uri = uri,
                onReady = {
                    try {
                        categoryRepository = CategoryRepository(this, uri)
                        refreshList()
                    } catch (e: Exception) {
                        categoryRepository = null
                        pickFolder()
                    }
                },
                onCancelled = { pickFolder() }
            )
        } else {
            pickFolder()
        }

        // ✅ 处理分享进来的内容
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleShareIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ===================== ✅ 接收分享 =====================

    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText.isNullOrEmpty()) return

        showSaveSharedTextDialog(sharedText)
    }

    private fun showSaveSharedTextDialog(text: String) {
        val repo = categoryRepository
        if (repo == null) {
            Toast.makeText(this, "请先解锁并选择文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ 不再依赖 500ms 硬编码延迟去猜"列表应该加载完了"
        // 而是在这里重新从磁盘拉一次最新分类，加载完再弹窗，避免分类没加载完就弹出空列表
        Thread {
            val freshCategories = repo.getAllCategories().toMutableList()
            runOnUiThread {
                categories = freshCategories
                adapter.submitEntries(categories)
                showSaveSharedTextDialogWithCategories(text)
            }
        }.start()
    }

    private fun showSaveSharedTextDialogWithCategories(text: String) {
        // ✅ 用自定义布局同时展示"内容预览"和"分类列表"
        // （AlertDialog 的 setMessage 和 setItems 内容区互斥，同时用会导致列表被隐藏）
        // 列表第一项固定是"➕ 新建分类"，分类为空时也能直接在这里新建，不用再单独跳出去
        val NEW_CATEGORY_LABEL = "➕ 新建分类"
        val listLabels = listOf(NEW_CATEGORY_LABEL) + categories.map { it.name }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val previewView = TextView(this).apply {
            this.text = "${text.take(150)}${if (text.length > 150) "..." else ""}"
            setPadding(48, 24, 48, 24)
        }
        val listView = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, listLabels)
        }
        container.addView(previewView)
        container.addView(listView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("📥 保存到哪个分类？")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                // 新建分类：先建好分类，成功后再用它保存笔记，并关掉本对话框
                showCreateCategoryThenSaveDialog(text, dialog)
                return@setOnItemClickListener
            }
            dialog.dismiss()
            promptNoteNameThenSave(text, categories[position - 1])
        }

        dialog.show()
    }

    private fun promptNoteNameThenSave(text: String, targetCategory: Category) {
        val defaultName = "分享_${System.currentTimeMillis() / 1000}"
        val editText = EditText(this).apply {
            setSingleLine(true)
        }
        editText.setText(defaultName)
        editText.setSelection(0, defaultName.length) // 全选，方便直接输入覆盖

        AlertDialog.Builder(this)
            .setTitle("笔记文件名")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val noteName = editText.text.toString().trim().ifEmpty { defaultName }
                saveSharedTextToNewNote(text, targetCategory, noteName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveSharedTextToNewNote(text: String, targetCategory: Category, noteName: String) {
        Toast.makeText(this, "正在保存...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val categoryDoc = DocumentFile.fromTreeUri(this, targetCategory.uri)
                if (categoryDoc != null) {
                    val repo = NoteRepository(this, categoryDoc)
                    val note = repo.addNote(noteName)
                    if (note != null) {
                        DocStore.setContent(this, note.uri, text)
                        runOnUiThread {
                            Toast.makeText(this, "✅ 已保存到「${targetCategory.name}」", Toast.LENGTH_LONG).show()
                            // 刷新列表
                            refreshList()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ===================== 原有代码 =====================

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

    /**
     * 解锁（或首次创建）指定文件夹的密钥。
     * 全部完成后在主线程回调 onReady()；用户主动取消密码框则回调 onCancelled()。
     */
    private fun ensureVaultUnlocked(uri: Uri, onReady: () -> Unit, onCancelled: () -> Unit) {
        val uriStr = uri.toString()
        Thread {
            // 1. 优先用本机 Keystore 缓存，免密解锁
            val cachedBytes = VaultKeyCache.load(this, uriStr)
            if (cachedBytes != null) {
                KeyManager.restoreFromCachedBytes(cachedBytes)
                runOnUiThread { onReady() }
                return@Thread
            }

            // 2. 没有缓存：判断这个文件夹是不是第一次使用，决定弹"设置密码"还是"输入密码"
            val exists = KeyManager.vaultExists(this, uri)
            runOnUiThread {
                promptPassword(
                    isNewVault = !exists,
                    onSubmit = { password, onWrongPassword ->
                        unlockWithPassword(uri, uriStr, exists, password, onReady, onWrongPassword, onCancelled)
                    },
                    onCancel = onCancelled
                )
            }
        }.start()
    }

    private fun unlockWithPassword(
        uri: Uri,
        uriStr: String,
        exists: Boolean,
        password: String,
        onReady: () -> Unit,
        onWrongPassword: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        Thread {
            if (exists) {
                when (KeyManager.unlock(this, uri, password)) {
                    is KeyManager.UnlockResult.Success -> {
                        KeyManager.getKeyBytesOrNull()?.let { VaultKeyCache.save(this, uriStr, it) }
                        runOnUiThread {
                            // ✅ 密码正确：关闭弹窗
                            passwordDialog?.dismiss()
                            passwordDialog = null
                            onReady()
                        }
                    }
                    is KeyManager.UnlockResult.WrongPassword -> {
                        runOnUiThread {
                            // ✅ 密码错误：恢复按钮和输入框状态
                            onWrongPassword("密码错误，请重试")
                        }
                    }
                    is KeyManager.UnlockResult.IoError -> {
                        runOnUiThread {
                            Toast.makeText(this, "读取密钥文件失败，请重新选择文件夹", Toast.LENGTH_LONG).show()
                            passwordDialog?.dismiss()
                            passwordDialog = null
                            onCancelled()
                        }
                    }
                }
            } else {
                val ok = KeyManager.createNew(this, uri, password)
                if (ok) {
                    KeyManager.getKeyBytesOrNull()?.let { VaultKeyCache.save(this, uriStr, it) }
                    runOnUiThread {
                        // ✅ 创建成功：关闭弹窗
                        passwordDialog?.dismiss()
                        passwordDialog = null
                        onReady()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "创建密钥失败，请重试", Toast.LENGTH_LONG).show()
                        passwordDialog?.dismiss()
                        passwordDialog = null
                        onCancelled()
                    }
                }
            }
        }.start()
    }

    /**
     * 弹出密码输入框。
     * isNewVault=true：首次设置密码（需要二次确认）；false：输入密码解锁。
     * onSubmit 的第二个参数用来在密码错误时显示错误提示，并让对话框继续留着重试。
     */
    private fun promptPassword(
        isNewVault: Boolean,
        onSubmit: (password: String, onWrongPassword: (String) -> Unit) -> Unit,
        onCancel: () -> Unit
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_password, null)
        val tvHint = view.findViewById<TextView>(R.id.tvHint)
        val editPassword = view.findViewById<EditText>(R.id.editPassword)
        val editConfirm = view.findViewById<EditText>(R.id.editConfirmPassword)
        val tvError = view.findViewById<TextView>(R.id.tvError)

        // ✅ 创建 ProgressBar 并添加到布局
        val progressBar = ProgressBar(view.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        // ✅ 安全地添加 ProgressBar 到容器
        if (view is ViewGroup) {
            view.addView(progressBar)
        }

        tvHint.text = if (isNewVault) {
            "首次使用，请设置一个密码。以后同一台设备无需再次输入；请务必记住，密码丢失将无法恢复笔记。"
        } else {
            "请输入密码解锁"
        }
        editConfirm.visibility = if (isNewVault) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNewVault) "设置密码" else "输入密码")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ ->
                progressBar.visibility = View.GONE
                onCancel()
            }
            .create()

        // ✅ 保存弹窗引用
        passwordDialog = dialog

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val pwd = editPassword.text.toString()
                if (pwd.isEmpty()) {
                    tvError.text = "密码不能为空"
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (isNewVault && pwd != editConfirm.text.toString()) {
                    tvError.text = "两次输入的密码不一致"
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                // ✅ 清除错误，隐藏确定按钮，显示加载进度
                tvError.visibility = View.GONE
                positiveButton.visibility = View.GONE  // 直接隐藏确定按钮
                progressBar.visibility = View.VISIBLE
                editPassword.isEnabled = false
                editConfirm.isEnabled = false

                onSubmit(pwd) { errorMsg ->
                    // ✅ 密码错误：恢复界面
                    positiveButton.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    editPassword.isEnabled = true
                    editConfirm.isEnabled = true
                    tvError.text = errorMsg
                    tvError.visibility = View.VISIBLE
                    editPassword.text.clear()
                    editPassword.requestFocus()
                }
            }
        }

        dialog.setOnCancelListener {
            passwordDialog = null
        }

        dialog.show()
    }

    // ---------------------------------------------------------------------

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

    private fun showCreateCategoryThenSaveDialog(text: String, parentDialog: AlertDialog) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_note, null)
        val editText = view.findViewById<EditText>(R.id.editName)
        AlertDialog.Builder(this)
            .setTitle("新建分类")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val repo = categoryRepository
                Thread {
                    val realCategory = repo?.addCategory(name)
                    runOnUiThread {
                        if (realCategory != null) {
                            categories.add(realCategory)
                            categories.sortWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
                            adapter.submitEntries(categories)
                            parentDialog.dismiss()
                            promptNoteNameThenSave(text, realCategory)
                        } else {
                            Toast.makeText(this, "新建分类失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
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
                    val folderDoc = DocumentFile.fromTreeUri(this, category.uri)
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
                     

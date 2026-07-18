package com.example.pinyinnotes

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 显示崩溃详情的页面，文字可长按复制 */
class ErrorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra("error_text") ?: "未知错误"

        val textView = TextView(this).apply {
            setText("拼音笔记崩溃了，报错内容如下（长按可复制）：\n\n$text")
            setTextIsSelectable(true)
            setPadding(32, 64, 32, 64)
            textSize = 13f
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        setContentView(scrollView)
    }
}

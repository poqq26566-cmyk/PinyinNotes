package com.example.pinyinnotes

import android.net.Uri

/**
 * 简单的内存缓存：记住每个分类最近一次的笔记列表。
 * 再次进入同一分类时先用缓存秒开，同时后台刷新真实数据。
 * App 进程被杀掉后缓存自动清空，不影响正确性。
 */
object NotesCache {
    private val cache = mutableMapOf<Uri, List<Note>>()

    fun get(categoryUri: Uri): List<Note>? = cache[categoryUri]

    fun put(categoryUri: Uri, notes: List<Note>) {
        cache[categoryUri] = notes
    }
}

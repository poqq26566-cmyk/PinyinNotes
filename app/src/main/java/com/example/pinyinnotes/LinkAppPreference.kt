package com.example.pinyinnotes

import android.content.Context
import android.net.Uri

/**
 * 记住"某条笔记"对应的链接默认打开方式（App包名），
 * 每条笔记（按 uri 区分）各自独立记录，随时可以重新选择更换。
 */
object LinkAppPreference {
    private const val PREFS = "link_app_prefs"

    fun get(context: Context, noteUri: Uri): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(noteUri.toString(), null)
    }

    fun set(context: Context, noteUri: Uri, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(noteUri.toString(), packageName).apply()
    }

    fun clear(context: Context, noteUri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(noteUri.toString()).apply()
    }
}

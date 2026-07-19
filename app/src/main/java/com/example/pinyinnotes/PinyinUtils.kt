package com.example.pinyinnotes

import android.icu.text.Transliterator
import android.os.Build

/**
 * 使用 Android 内置 ICU 库（Han-Latin 转写）获取中文名称首字的拼音首字母，
 * 无需引入任何第三方拼音库。
 */
object PinyinUtils {

    private var transliterator: Transliterator? = null

    private fun getTransliterator(): Transliterator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (transliterator == null) {
                transliterator = Transliterator.getInstance("Han-Latin")
            }
            return transliterator
        }
        return null
    }

    /** 返回名称首字符对应的拼音首字母（大写）；非中文/非字母开头返回 "#" */
    fun getFirstLetter(name: String): String {
        if (name.isEmpty()) return "#"
        val firstChar = name[0]

        if (firstChar.isLetter() && firstChar.code < 128) {
            return firstChar.uppercaseChar().toString()
        }

        val trans = getTransliterator()
        if (trans != null) {
            val pinyin = trans.transliterate(firstChar.toString())
            // 去掉拼音声调符号（如 à -> a），避免分组和排序错乱
            val stripped = java.text.Normalizer.normalize(pinyin, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            val letter = stripped.firstOrNull { it.isLetter() && it.code < 128 }
            if (letter != null) {
                return letter.uppercaseChar().toString()
            }
        }
        return "#"
    }
}

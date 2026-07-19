package com.example.pinyinnotes

import android.icu.text.Transliterator
import android.os.Build

object PinyinUtils {

    private var transliterator: Transliterator? = null
    // ✅ 修复8：缓存已计算过的首字母，避免重复 transliterate + normalize
    private val letterCache = HashMap<Char, String>(256)

    private fun getTransliterator(): Transliterator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (transliterator == null) {
                transliterator = Transliterator.getInstance("Han-Latin")
            }
            return transliterator
        }
        return null
    }

    fun getFirstLetter(name: String): String {
        if (name.isEmpty()) return "#"
        val firstChar = name[0]

        if (firstChar.isLetter() && firstChar.code < 128) {
            return firstChar.uppercaseChar().toString()
        }

        // ✅ 先查缓存
        letterCache[firstChar]?.let { return it }

        val trans = getTransliterator()
        val result = if (trans != null) {
            val pinyin = trans.transliterate(firstChar.toString())
            val stripped = java.text.Normalizer.normalize(pinyin, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            val letter = stripped.firstOrNull { it.isLetter() && it.code < 128 }
            letter?.uppercaseChar()?.toString() ?: "#"
        } else {
            "#"
        }

        // ✅ 写入缓存
        letterCache[firstChar] = result
        return result
    }
}

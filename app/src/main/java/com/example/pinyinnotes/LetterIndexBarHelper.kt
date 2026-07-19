package com.example.pinyinnotes

import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 右侧字母索引条：点击或上下滑动跳转到对应分组，"#" 代表非字母开头 */
object LetterIndexBarHelper {

    private val LETTERS = listOf("#") + ('A'..'Z').map { it.toString() }

    fun setup(
        container: LinearLayout,
        recyclerView: RecyclerView,
        getPositionForLetter: (String) -> Int
    ) {
        container.removeAllViews()
        LETTERS.forEach { letter ->
            val tv = TextView(container.context)
            tv.text = letter
            tv.textSize = 11f
            tv.gravity = Gravity.CENTER
            tv.setPadding(2, 2, 2, 2)
            container.addView(tv)
        }

        fun jumpTo(index: Int) {
            if (index < 0 || index >= LETTERS.size) return
            val pos = getPositionForLetter(LETTERS[index])
            if (pos >= 0) {
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, 0)
            }
        }

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (view.height > 0) {
                        val itemHeight = view.height / LETTERS.size.toFloat()
                        val index = (event.y / itemHeight).toInt()
                        jumpTo(index)
                    }
                    true
                }
                else -> false
            }
        }
    }
}

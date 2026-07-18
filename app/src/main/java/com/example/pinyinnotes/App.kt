package com.example.pinyinnotes

import android.app.Application
import android.content.Intent

/** 全局捕获崩溃，跳转到 ErrorActivity 显示具体报错，方便截图反馈 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                val intent = Intent(this, ErrorActivity::class.java)
                intent.putExtra("error_text", trace)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            } catch (e: Exception) {
                // 忽略
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }
}

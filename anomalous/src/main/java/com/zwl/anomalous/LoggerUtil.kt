package com.zwl.anomalous

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.orhanobut.logger.*
import timber.log.Timber
import timber.log.Timber.Forest.plant


object LoggerUtil {
    fun install(context: Context) {
        // 初始化日志
        Logger.addLogAdapter(object : AndroidLogAdapter() {
            override fun isLoggable(priority: Int, tag: String?): Boolean {
                return BuildConfig.DEBUG
            }
        })


        var logStrategy: LogStrategy? = null
        context.getExternalFilesDir(null)?.absolutePath?.let { folder ->
            val ht = HandlerThread("AndroidFileLogger.$folder")
            ht.start()
            val MAX_BYTES = 500 * 1024 // 500K averages to a 4000 lines per file

            val handler: Handler = DiskLogStrategy.WriteHandler(ht.looper, folder, MAX_BYTES)
            logStrategy = DiskLogStrategy(handler)
        }
        val formatStrategy: FormatStrategy = CsvFormatStrategy.newBuilder()
            .logStrategy(logStrategy)
            .build()
        Logger.addLogAdapter(object : DiskLogAdapter(formatStrategy) {
            override fun isLoggable(priority: Int, tag: String?): Boolean {
                return if (BuildConfig.DEBUG) priority == Logger.DEBUG || priority == Logger.ERROR else priority == Logger.ERROR
            }
        })

        // Set methodOffset to 5 in order to hide internal method calls
        plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                Logger.log(priority, tag, message, t)
            }
        })
    }
}
package com.example.crashlog

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.orhanobut.logger.Logger
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Timber.plant( Timber.DebugTree())
    }
    fun onClick(view: View) {
        if(R.id.bt_exception == view.id) {
            throw RuntimeException("I'm a test exception!")
        }else if(R.id.bt_log == view.id) {
            Logger.d("哈哈哈")
            Logger.e("呵呵呵")
            Timber.tag("aaaa")
            Timber.d("嘻嘻嘻")
            Timber.e("嚯嚯嚯")
        }
    }
}
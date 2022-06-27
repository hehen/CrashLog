package com.zwl.anomalous

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zwl.anomalous.Anomalous.getAllErrorDetailsFromIntent
import com.zwl.anomalous.Anomalous.getAppName
import com.zwl.anomalous.Anomalous.killCurrentProcess

class AnomalousActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anomalous)

        //Close/restart button logic:
        //If a class if set, use restart.
        //Else, use close and just finish the app.
        //It is recommended that you follow this logic if implementing a custom error activity.
        val restartButton = findViewById<Button>(R.id.crash_error_activity_restart_button)
        val closeButton = findViewById<Button>(R.id.crash_error_activity_close_button)
        val tvCrashTool = findViewById<TextView>(R.id.rx_crash_tool)

        restartButton.setOnClickListener {
            val intent = Intent(
                this@AnomalousActivity,
                Anomalous.guessRestartActivityClass(this@AnomalousActivity)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            finish()
        }
        closeButton.setOnClickListener { finish() }

        val message = getAllErrorDetailsFromIntent(this@AnomalousActivity, intent)

        val appName = getAppName(this)
        tvCrashTool.text = appName
        val locateButton = findViewById<TextView>(R.id.crash_error_locate_more_info_button)
//        locateButton.text = "${locateButton.text}\n${file.absolutePath}\n"
        val moreInfoButton = findViewById<Button>(R.id.crash_error_activity_more_info_button)
        moreInfoButton.setOnClickListener { //We retrieve all the error data and show it
            val dialog = AlertDialog.Builder(this@AnomalousActivity)
                .setTitle("异常信息")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制到剪贴板") { dialog, which -> copyErrorToClipboard() }
                .show()
        }
    }

    private fun copyErrorToClipboard() {
        val errorInformation = getAllErrorDetailsFromIntent(this@AnomalousActivity, intent)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        //Are there any devices without clipboard...?
        val clip = ClipData.newPlainText("错误信息", errorInformation)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this@AnomalousActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
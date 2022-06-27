package com.zwl.anomalous

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.orhanobut.logger.Logger
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipFile

object Anomalous {
    private const val INTENT_ACTION_RESTART_ACTIVITY = "RESTART";

    private const val EXTRA_STACK_TRACE =
        "EXTRA_STACK_TRACE"
    private const val EXTRA_ACTIVITY_LOG =
        "EXTRA_ACTIVITY_LOG"
    private const val MAX_STACK_TRACE_SIZE = 131071 //128 KB - 1
    private const val MIN_SHOW_CRASH_INTERVAL = 3000

    private const val MAX_ACTIVITIES_IN_LOG = 50
    private const val INTENT_ACTION_ERROR_ACTIVITY = "com.zwl.anomalous.ERROR"

    //Shared preferences
    private const val SHARED_PREFERENCES_FILE = "TCrashTool"
    private const val SHARED_PREFERENCES_FIELD_TIMESTAMP = "last_crash_timestamp"
    private val activityLog: Deque<String> = ArrayDeque<String>(MAX_ACTIVITIES_IN_LOG)
    private var lastActivityCreated = WeakReference<Activity?>(null)

    @JvmStatic
    fun install(context: Context) {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (oldHandler != null && !oldHandler.javaClass.name.startsWith(javaClass.name)) {
//                if (!hasCrashedInTheLastSeconds(context.applicationContext)) {
//                    setLastCrashTimestamp(
//                        context.applicationContext,
//                        Date().time
//                    )
                var errorActivityClass = guessErrorActivityClass(context.applicationContext)

                val intent = Intent(context.applicationContext, errorActivityClass)
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                var stackTraceString = sw.toString()

                //Reduce data to 128KB so we don't get a TransactionTooLargeException when sending the intent.
                //The limit is 1MB on Android but some devices seem to have it lower.
                //See: http://developer.android.com/reference/android/os/TransactionTooLargeException.html
                //And: http://stackoverflow.com/questions/11451393/what-to-do-on-transactiontoolargeexception#comment46697371_12809171
                if (stackTraceString.length > MAX_STACK_TRACE_SIZE) {
                    val disclaimer = " [stack trace too large]"
                    stackTraceString = stackTraceString.substring(
                        0,
                        MAX_STACK_TRACE_SIZE - disclaimer.length
                    ) + disclaimer
                }
                intent.putExtra(
                    EXTRA_STACK_TRACE,
                    stackTraceString
                )
                val activityLogStringBuilder = StringBuilder()
                while (!activityLog.isEmpty()) {
                    activityLogStringBuilder.append(activityLog.poll())
                }
                intent.putExtra(
                    EXTRA_ACTIVITY_LOG,
                    activityLogStringBuilder.toString()
                )

                getAllErrorDetailsFromIntent(context.applicationContext,intent)?.let { Logger.e(it) }

                intent.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(intent)

                val lastActivity: Activity? = lastActivityCreated.get()
                if (lastActivity != null) {
                    //We finish the activity, this solves a bug which causes infinite recursion.
                    //See: https://github.com/ACRA/acra/issues/42
                    lastActivity.finish()
                    lastActivityCreated.clear()
                }
                //com.android.internal.os.RuntimeInit$UncaughtHandler
                if (!oldHandler.javaClass.name.startsWith("com.android.internal.os.RuntimeInit")) {
                    oldHandler.uncaughtException(t, e)
                } else {
                    e.printStackTrace()
                    killCurrentProcess()
                }
//                } else {
//                    oldHandler.uncaughtException(t, e)
//                }
            } else {
                oldHandler?.uncaughtException(t, e)
            }
        }
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                val dateFormat: DateFormat =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                var currentlyStartedActivities = 0
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                    if (activity.javaClass != getErrorActivityClassWithIntentFilter(context.applicationContext)) {
                        // Copied from ACRA:
                        // Ignore activityClass because we want the last
                        // application Activity that was started so that we can
                        // explicitly kill it off.
                        lastActivityCreated = WeakReference(activity)
                    }
                    activityLog.add(
                        """${dateFormat.format(Date())}: ${activity.javaClass.simpleName} created
"""
                    )
                }

                override fun onActivityStarted(activity: Activity) {
                    currentlyStartedActivities++
                }

                override fun onActivityResumed(activity: Activity) {
                    activityLog.add(
                        """${dateFormat.format(Date())}: ${activity.javaClass.simpleName} resumed
"""
                    )
                }

                override fun onActivityPaused(activity: Activity) {
                    activityLog.add(
                        """${dateFormat.format(Date())}: ${activity.javaClass.simpleName} paused
"""
                    )
                }

                override fun onActivityStopped(activity: Activity) {
                    //Do nothing
                    currentlyStartedActivities--
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                    //Do nothing
                }

                override fun onActivityDestroyed(activity: Activity) {
                    activityLog.add(
                        """${dateFormat.format(Date())}: ${activity.javaClass.simpleName} destroyed
"""
                    )
                }
            })
    }

    private fun hasCrashedInTheLastSeconds(context: Context): Boolean {
        val lastTimestamp: Long = getLastCrashTimestamp(context)
        val currentTimestamp = Date().time
        return lastTimestamp <= currentTimestamp && currentTimestamp - lastTimestamp < MIN_SHOW_CRASH_INTERVAL
    }

    private fun getLastCrashTimestamp(context: Context): Long {
        return context.getSharedPreferences(
            SHARED_PREFERENCES_FILE,
            Context.MODE_PRIVATE
        ).getLong(SHARED_PREFERENCES_FIELD_TIMESTAMP, -1)
    }

    private fun setLastCrashTimestamp(context: Context, timestamp: Long) {
        context.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(SHARED_PREFERENCES_FIELD_TIMESTAMP, timestamp)
            .commit()
    }

    private fun guessErrorActivityClass(context: Context): Class<out Activity?> {
        var resolvedActivityClass: Class<out Activity?>?

        //If action is defined, use that
        resolvedActivityClass = getErrorActivityClassWithIntentFilter(context)

        //Else, get the default error activity
        if (resolvedActivityClass == null) {
            resolvedActivityClass = AnomalousActivity::class.java
        }
        return resolvedActivityClass
    }

    private fun getErrorActivityClassWithIntentFilter(context: Context): Class<out Activity?>? {
        val searchedIntent =
            Intent().setAction(INTENT_ACTION_ERROR_ACTIVITY)
                .setPackage(context.packageName)
        val resolveInfos = context.packageManager.queryIntentActivities(
            searchedIntent,
            PackageManager.GET_RESOLVED_FILTER
        )
        if (resolveInfos.size > 0) {
            val resolveInfo = resolveInfos[0]
            try {
                return Class.forName(resolveInfo.activityInfo.name) as Class<out Activity?>
            } catch (e: ClassNotFoundException) {
                e.printStackTrace()
            }
        }
        return null
    }

    fun guessRestartActivityClass(context: Context): Class<out Activity?>? {
        var resolvedActivityClass: Class<out Activity?>?

        //If action is defined, use that
        resolvedActivityClass = getRestartActivityClassWithIntentFilter(context)

        //Else, get the default launcher activity
        if (resolvedActivityClass == null) {
            resolvedActivityClass = getLauncherActivity(context)
        }
        return resolvedActivityClass
    }

    private fun getRestartActivityClassWithIntentFilter(context: Context): Class<out Activity?>? {
        val searchedIntent =
            Intent().setAction(INTENT_ACTION_RESTART_ACTIVITY)
                .setPackage(context.packageName)
        val resolveInfos = context.packageManager.queryIntentActivities(
            searchedIntent,
            PackageManager.GET_RESOLVED_FILTER
        )
        if (resolveInfos.size > 0) {
            val resolveInfo = resolveInfos[0]
            try {
                return Class.forName(resolveInfo.activityInfo.name) as Class<out Activity?>
            } catch (e: ClassNotFoundException) {
                e.printStackTrace()
            }
        }
        return null
    }

    private fun getLauncherActivity(context: Context): Class<out Activity?>? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null && intent.component != null) {
            try {
                return Class.forName(intent.component!!.className) as Class<out Activity?>
            } catch (e: ClassNotFoundException) {
                e.printStackTrace()
            }
        }
        return null
    }

    fun getAllErrorDetailsFromIntent(context: Context, intent: Intent): String? {
        //I don't think that this needs localization because it's a development string...
        val currentDate = Date()
        val dateFormat: DateFormat = SimpleDateFormat("yyyy年MM月dd日 HH点mm分ss秒", Locale.CHINA)

        //Get build date
        val buildDateAsString: String? = getBuildDateAsString(context, dateFormat)

        //Get app version
        val versionName: String? = getVersionName(context)
        val appName: String? = getAppName(context)
        val packageName: String? = getPackageName(context)
        var errorDetails = ""
        errorDetails += "Build App Name : $appName \n"
        errorDetails += "Build version : $versionName \n"
        errorDetails += "Build Package Name : $packageName \n"
        if (buildDateAsString != null) {
            errorDetails += "Build date : $buildDateAsString \n"
        }
        errorDetails += """Current date : ${dateFormat.format(currentDate)} 
"""
        //Added a space between line feeds to fix #18.
        //Ideally, we should not use this method at all... It is only formatted this way because of coupling with the default error activity.
        //We should move it to a method that returns a bean, and let anyone format it as they wish.
        errorDetails += """Device : ${getDeviceModelName()} 
"""
        errorDetails += """OS version : Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) 
 
"""
        errorDetails += "Stack trace :  \n"
        errorDetails += getStackTraceFromIntent(intent)
        val activityLog: String? = getActivityLogFromIntent(intent)
        if (activityLog != null) {
            errorDetails += "\nUser actions : \n"
            errorDetails += activityLog
        }
        return errorDetails
    }

    private fun getBuildDateAsString(context: Context, dateFormat: DateFormat): String? {
        var buildDate: Long
        try {
            val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
            val zf = ZipFile(ai.sourceDir)

            //If this failed, try with the old zip method
            val ze = zf.getEntry("classes.dex")
            buildDate = ze.time
            zf.close()
        } catch (e: Exception) {
            buildDate = 0
        }
        return if (buildDate > 312764400000L) {
            dateFormat.format(Date(buildDate))
        } else {
            null
        }
    }

    private fun getVersionName(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: java.lang.Exception) {
            "Unknown"
        }
    }

    fun getAppName(context: Context, packageName: String): String? {
        return if (isNullString(packageName)) null else try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(packageName, 0)
            pi?.applicationInfo?.loadLabel(pm)?.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    fun getAppName(context: Context): String? {
        val appName: String? = getAppName(context, context.packageName)
        return if (isNullString(appName)) {
            "Unknown"
        } else {
            appName
        }
    }

    private fun getPackageName(context: Context): String? {
        val appName = context.packageName
        return if (isNullString(appName)) {
            "Unknown"
        } else {
            appName
        }
    }

    private fun getDeviceModelName(): String? {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            capitalize(model)
        } else {
            capitalize(manufacturer) + " " + model
        }
    }

    private fun capitalize(s: String?): String? {
        if (s == null || s.length == 0) {
            return ""
        }
        val first = s[0]
        return if (Character.isUpperCase(first)) {
            s
        } else {
            first.uppercaseChar().toString() + s.substring(1)
        }
    }

    fun isNullString(str: String?): Boolean {
        return str == null || str.length == 0 || "null" == str
    }

    fun getStackTraceFromIntent(intent: Intent): String? {
        return intent.getStringExtra(EXTRA_STACK_TRACE)
    }

    fun getActivityLogFromIntent(intent: Intent): String? {
        return intent.getStringExtra(EXTRA_ACTIVITY_LOG)
    }

    fun killCurrentProcess() {
        Process.killProcess(Process.myPid())
        System.exit(10)
    }
}
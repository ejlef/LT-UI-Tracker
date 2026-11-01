
package com.fnc.liveuitracker

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.LruCache
import android.util.Log

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {

    private val methodChannelName = "live_tracker/methods"
    private val notificationRequestCode = 2001

    private var notificationResult: MethodChannel.Result? = null

    private val appIconCache = object : LruCache<String, String>(200) {}

    // ============================================================
    // FLUTTER METHOD CHANNEL
    // ============================================================

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            methodChannelName
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "hasUsageAccess" -> result.success(hasUsageAccess())

                "openUsageSettings" -> {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    result.success(true)
                }

                "hasOverlayPermission" -> {
                    result.success(
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                            Settings.canDrawOverlays(this)
                    )
                }

                "openOverlaySettings" -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    result.success(true)
                }

                "hasNotificationPermission" -> {
                    result.success(hasNotificationPermission())
                }

                "requestNotificationPermission" -> {
                    requestNotificationPermission(result)
                }

                "hasBatteryOptimizationExemption" -> {
                    result.success(hasBatteryOptimizationExemption())
                }

                "openBatterySettings" -> {
                    openBatterySettings()
                    result.success(true)
                }

                // START TRACKER
                "startTracker" -> {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        !Settings.canDrawOverlays(this)
                    ) {
                        result.error(
                            "OVERLAY_PERMISSION",
                            "Allow display over other apps first.",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    try {
                        Log.d("LIVE_TRACKER", "Start Tracker requested")

                        val serviceIntent = Intent(
                            this,
                            OverlayTrackerService::class.java
                        ).apply {
                            action = "com.example.adware_removal.SHOW_BUBBLE"
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }

                        Log.d(
                            "LIVE_TRACKER",
                            "Tracker service start request sent"
                        )

                        result.success(true)

                    } catch (e: Exception) {
                        Log.e("LIVE_TRACKER", "Unable to start tracker", e)

                        result.error(
                            "START_TRACKER_FAILED",
                            e.message ?: "Unable to start tracker service",
                            null
                        )
                    }
                }

                // STOP TRACKER
                "stopTracker" -> {
                    try {
                        stopService(
                            Intent(this, OverlayTrackerService::class.java)
                        )

                        result.success(true)

                    } catch (e: Exception) {
                        result.error(
                            "STOP_TRACKER_FAILED",
                            e.message ?: "Unable to stop tracker",
                            null
                        )
                    }
                }

                // CHECK TRACKER SERVICE STATUS
                "isTrackerRunning" -> {
                    val running = getSharedPreferences(
                        "live_tracker_service",
                        Context.MODE_PRIVATE
                    ).getBoolean(
                        OverlayTrackerService.KEY_TRACKER_RUNNING,
                        false
                    )

                    result.success(running)
                }

                // CURRENT FOREGROUND APP
                "getCurrentApp" -> {
                    Thread {
                        try {
                            val current = getLastExternalApp()

                            runOnUiThread {
                                result.success(current)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                result.error(
                                    "CURRENT_APP_ERROR",
                                    e.message,
                                    null
                                )
                            }
                        }
                    }.start()
                }

                // LAZY APP ICON LOADING
                "getAppIcon" -> {
                    val target = call.argument<String>("packageName")

                    if (!isValidPackage(target)) {
                        result.error(
                            "INVALID_PACKAGE",
                            "Invalid package.",
                            null
                        )
                    } else {
                        Thread {
                            val icon = getAppIconBase64(target!!)

                            runOnUiThread {
                                result.success(icon)
                            }
                        }.start()
                    }
                }

                // ANDROID APP INFORMATION
                "openAppInfo" -> {
                    val target = call.argument<String>("packageName")

                    if (!isValidPackage(target)) {
                        result.error(
                            "INVALID_PACKAGE",
                            "Invalid package.",
                            null
                        )
                    } else {
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$target")
                                )
                            )
                            result.success(true)
                        } catch (e: Exception) {
                            result.error(
                                "OPEN_SETTINGS_FAILED",
                                e.message,
                                null
                            )
                        }
                    }
                }

                // INSTALLED APPS - BACKGROUND THREAD
                "getInstalledApps" -> {
                    Thread {
                        try {
                            val apps = getInstalledApps()

                            runOnUiThread {
                                result.success(apps)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                result.error(
                                    "APP_LIST_ERROR",
                                    e.message,
                                    null
                                )
                            }
                        }
                    }.start()
                }

                // ROOT DETECTION
                "hasRootAccess" -> {
                    Thread {
                        val available = isRootAvailable()

                        runOnUiThread {
                            result.success(available)
                        }
                    }.start()
                }

                // NORMAL ANDROID UNINSTALL
                "uninstallApp" -> {
                    val target = call.argument<String>("packageName")?.trim()

                    Log.d("LIVE_TRACKER", "Uninstall requested: $target")

                    if (target.isNullOrEmpty() || !isValidPackage(target)) {
                        result.error(
                            "INVALID_PACKAGE",
                            "Invalid package name",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    if (target == packageName) {
                        result.error(
                            "UNINSTALL_SELF",
                            "Cannot uninstall this app",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    try {
                        val uninstallIntent = Intent(
                            Intent.ACTION_UNINSTALL_PACKAGE
                        ).apply {
                            data = Uri.parse("package:$target")
                            putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        }

                        Log.d(
                            "LIVE_TRACKER",
                            "Launching ACTION_UNINSTALL_PACKAGE: $target"
                        )

                        startActivityForResult(uninstallIntent, 2001)

                        result.success("UNINSTALL_SCREEN_OPENED")

                    } catch (e: Exception) {
                        Log.e("LIVE_TRACKER", "Uninstall launch failed", e)

                        result.error(
                            "UNINSTALL_LAUNCH_FAILED",
                            e.message ?: "Unable to launch uninstall screen",
                            null
                        )
                    }
                }

                // ROOT FORCE UNINSTALL
                "forceUninstallApp" -> {
                    val requested = call.argument<String>("packageName")

                    val target = requested?.takeIf {
                        isValidPackage(it) && it != packageName
                    }

                    if (target == null) {
                        result.error(
                            "INVALID_PACKAGE",
                            "Invalid package.",
                            null
                        )
                        return@setMethodCallHandler
                    }

                    Thread {
                        try {
                            val output = executeRootCommand(
                                "pm uninstall --user 0 $target"
                            )

                            val success = output.contains(
                                "Success",
                                ignoreCase = true
                            )

                            if (success) {
                                appIconCache.remove(target)
                            }

                            runOnUiThread {
                                result.success(
                                    mapOf(
                                        "success" to success,
                                        "packageName" to target,
                                        "output" to output
                                    )
                                )
                            }

                        } catch (e: Exception) {
                            runOnUiThread {
                                result.success(
                                    mapOf(
                                        "success" to false,
                                        "packageName" to target,
                                        "output" to (
                                            e.message
                                                ?: "Root uninstall failed."
                                        )
                                    )
                                )
                            }
                        }
                    }.start()
                }

                else -> result.notImplemented()
            }
        }
    }

    // ============================================================
    // NORMAL UNINSTALL RESULT
    // ============================================================

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 2001) {
            Log.d(
                "LIVE_TRACKER",
                "Uninstall activity closed. Result code: $resultCode"
            )
        }
    }

    // ============================================================
    // REAL ANDROID APPLICATION ICON
    // ============================================================

    private fun getAppIconBase64(targetPackage: String): String? {
        return try {
            appIconCache.get(targetPackage)?.let {
                return it
            }

            val drawable = packageManager.getApplicationIcon(targetPackage)

            val bitmap = Bitmap.createBitmap(
                96,
                96,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)

            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)

            val output = ByteArrayOutputStream()

            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                output
            )

            bitmap.recycle()

            val encoded = Base64.encodeToString(
                output.toByteArray(),
                Base64.NO_WRAP
            )

            appIconCache.put(targetPackage, encoded)

            encoded

        } catch (e: Exception) {
            null
        }
    }

    // ============================================================
    // NOTIFICATION PERMISSION
    // ============================================================

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission(
        result: MethodChannel.Result
    ) {
        if (
            Build.VERSION.SDK_INT < 33 ||
            hasNotificationPermission()
        ) {
            result.success(true)
            return
        }

        if (notificationResult != null) {
            result.error(
                "REQUEST_IN_PROGRESS",
                "A permission request is already active.",
                null
            )
            return
        }

        notificationResult = result

        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            notificationRequestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == notificationRequestCode) {
            val granted = hasNotificationPermission()

            notificationResult?.success(granted)
            notificationResult = null
        }
    }

    // ============================================================
    // BATTERY SETTINGS
    // ============================================================

    private fun hasBatteryOptimizationExemption(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        val powerManager = getSystemService(
            Context.POWER_SERVICE
        ) as android.os.PowerManager

        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )

            startActivity(intent)

        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ============================================================
    // PACKAGE VALIDATION
    // ============================================================

    private fun isValidPackage(target: String?): Boolean {
        if (target.isNullOrBlank()) return false

        return Regex(
            "^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"
        ).matches(target)
    }

    // ============================================================
    // USAGE ACCESS
    // ============================================================

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(
            Context.APP_OPS_SERVICE
        ) as AppOpsManager

        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ============================================================
    // ROOT ACCESS
    // ============================================================

    private fun isRootAvailable(): Boolean {
        return try {
            executeRootCommand("id").contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun executeRootCommand(command: String): String {
        val process = ProcessBuilder(
            "su",
            "-c",
            command
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use {
            it.readText()
        }

        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            output
        } else {
            "$output\nExit code: $exitCode"
        }
    }

    // ============================================================
    // APPLICATION NAME
    // ============================================================

    private fun getApplicationName(target: String): String {
        return try {
            val info = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                packageManager.getApplicationInfo(
                    target,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(target, 0)
            }

            packageManager.getApplicationLabel(info).toString()

        } catch (e: Exception) {
            target
        }
    }

    // ============================================================
    // INSTALLED APPLICATIONS
    // ============================================================

    private fun getInstalledApps(): List<Map<String, Any?>> {
        val pm = packageManager

        val applications = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        return applications.mapNotNull { appInfo ->
            try {
                val target = appInfo.packageName

                val packageInfo = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ) {
                    pm.getPackageInfo(
                        target,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(target, 0)
                }

                val systemApp =
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                mapOf(
                    "appName" to pm.getApplicationLabel(appInfo).toString(),
                    "packageName" to target,
                    "versionName" to packageInfo.versionName.orEmpty(),
                    "versionCode" to packageInfo.longVersionCode,
                    "firstInstallTime" to packageInfo.firstInstallTime,
                    "lastUpdateTime" to packageInfo.lastUpdateTime,
                    "isSystemApp" to systemApp,
                    "canUninstall" to (
                        !systemApp && target != packageName
                    )
                )

            } catch (e: Exception) {
                null
            }
        }.sortedBy {
            it["appName"]?.toString()?.lowercase() ?: ""
        }
    }

    // ============================================================
    // CURRENT FOREGROUND APPLICATION
    // ============================================================

    private fun getLastExternalApp(): Map<String, Any?> {
        val usageManager = getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

        val end = System.currentTimeMillis()
        val start = end - 15 * 60 * 1000L

        val events = usageManager.queryEvents(start, end)
        val event = UsageEvents.Event()

        var selectedPackage: String? = null
        var selectedTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val foreground =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType ==
                        UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    event.eventType ==
                        UsageEvents.Event.MOVE_TO_FOREGROUND
                }

            if (
                foreground &&
                event.packageName != packageName &&
                event.timeStamp >= selectedTime
            ) {
                selectedPackage = event.packageName
                selectedTime = event.timeStamp
            }
        }

        val target = selectedPackage ?: return mapOf(
            "appName" to "No application detected",
            "packageName" to "",
            "detectedAt" to 0L,
            "iconBase64" to null
        )

        return mapOf(
            "appName" to getApplicationName(target),
            "packageName" to target,
            "detectedAt" to selectedTime,
            "iconBase64" to getAppIconBase64(target)
        )
    }
}

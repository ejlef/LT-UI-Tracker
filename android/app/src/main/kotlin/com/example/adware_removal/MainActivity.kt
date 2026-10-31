
package com.example.adware_removal

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val methodChannelName = "live_tracker/methods"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            methodChannelName
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "hasUsageAccess" -> {
                    result.success(hasUsageAccess())
                }

                "openUsageSettings" -> {
                    startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
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

                    val serviceIntent = Intent(
                        this,
                        OverlayTrackerService::class.java
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    result.success(true)
                }

                "stopTracker" -> {
                    stopService(
                        Intent(this, OverlayTrackerService::class.java)
                    )
                    result.success(true)
                }

                "getCurrentApp" -> {
                    result.success(getLastExternalApp())
                }

                "openAppInfo" -> {
                    val targetPackage =
                        call.argument<String>("packageName")

                    if (targetPackage.isNullOrBlank()) {
                        result.error(
                            "INVALID_PACKAGE",
                            "Package name is missing.",
                            null
                        )
                    } else {
                        try {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$targetPackage")
                            )

                            startActivity(intent)
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

                else -> {
                    result.notImplemented()
                }
            }
        }
    }

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

            val isForegroundEvent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType ==
                        UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    event.eventType ==
                        UsageEvents.Event.MOVE_TO_FOREGROUND
                }

            if (
                isForegroundEvent &&
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
            "detectedAt" to 0L
        )

        val appName = try {
            val info = packageManager.getApplicationInfo(target, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            target
        }

        return mapOf(
            "appName" to appName,
            "packageName" to target,
            "detectedAt" to selectedTime
        )
    }
}

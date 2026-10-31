
package com.example.adware_removal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayTrackerService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: LinearLayout? = null
    private var appNameText: TextView? = null
    private var packageText: TextView? = null

    private var currentPackage = ""
    private var isPanelOpen = false

    private val updateTask = object : Runnable {
        override fun run() {
            updateDetectedApp()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startTrackerForeground()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)
        ) {
            createBubble()
            handler.post(updateTask)
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    private fun startTrackerForeground() {

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Live UI Tracker")
            .setContentText("Application tracking is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live UI Tracker",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    // Get the full screen dimensions.
    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        return metrics
    }

    // Create draggable floating bubble.
    private fun createBubble() {

        val bubbleSize = 58
        val edgeMargin = 16

        val metrics = getScreenMetrics()

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {

            // Important: use TOP + LEFT for full-screen movement.
            gravity = Gravity.TOP or Gravity.LEFT

            x = (metrics.widthPixels - bubbleSize - edgeMargin)
                .coerceAtLeast(0)

            y = ((metrics.heightPixels - bubbleSize) / 2)
                .coerceAtLeast(0)
        }

        bubbleParams = params

        bubble = TextView(this).apply {

            text = "LT"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            setBackgroundColor(
                Color.rgb(25, 105, 210)
            )

            elevation = 12f

            val touchSlop =
                ViewConfiguration.get(
                    this@OverlayTrackerService
                ).scaledTouchSlop

            var startX = 0
            var startY = 0

            var downRawX = 0f
            var downRawY = 0f

            var isDragging = false

            // Tap opens panel; dragging only moves the bubble.
            setOnTouchListener { view, event ->

                val currentParams =
                    bubbleParams ?: return@setOnTouchListener false

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        startX = currentParams.x
                        startY = currentParams.y

                        downRawX = event.rawX
                        downRawY = event.rawY

                        isDragging = false

                        true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY

                        if (
                            !isDragging &&
                            (
                                abs(deltaX) > touchSlop ||
                                abs(deltaY) > touchSlop
                            )
                        ) {
                            isDragging = true
                        }

                        if (isDragging) {

                            val screen = getScreenMetrics()

                            val maxX = (
                                screen.widthPixels -
                                currentParams.width
                            ).coerceAtLeast(0)

                            val maxY = (
                                screen.heightPixels -
                                currentParams.height
                            ).coerceAtLeast(0)

                            // Move horizontally across the full screen.
                            currentParams.x = (
                                startX + deltaX.toInt()
                            ).coerceIn(0, maxX)

                            // Move vertically across the full screen.
                            currentParams.y = (
                                startY + deltaY.toInt()
                            ).coerceIn(0, maxY)

                            try {
                                windowManager.updateViewLayout(
                                    view,
                                    currentParams
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        true
                    }

                    MotionEvent.ACTION_UP -> {

                        if (!isDragging) {
                            view.performClick()
                        }

                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        true
                    }

                    else -> false
                }
            }

            setOnClickListener {
                togglePanel()
            }
        }

        windowManager.addView(bubble, params)
    }

    // Create information panel.
    private fun createPanel() {

        val container = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(22, 18, 22, 18)

            setBackgroundColor(
                Color.rgb(25, 30, 42)
            )

            elevation = 18f
        }

        val heading = TextView(this).apply {

            text = "LIVE UI TRACKER"
            textSize = 13f

            setTextColor(
                Color.rgb(100, 170, 255)
            )

            typeface = Typeface.DEFAULT_BOLD
        }

        appNameText = TextView(this).apply {

            text = "Detecting application..."
            textSize = 20f

            setTextColor(Color.WHITE)

            typeface = Typeface.DEFAULT_BOLD

            setPadding(0, 14, 0, 4)
        }

        packageText = TextView(this).apply {

            text = "Waiting for application"
            textSize = 11f

            setTextColor(Color.LTGRAY)
        }

        val settingsButton = Button(this).apply {

            text = "Open App Info"

            setOnClickListener {

                if (currentPackage.isNotBlank()) {

                    try {

                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$currentPackage")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        startActivity(intent)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val trackerButton = Button(this).apply {

            text = "Open Tracker Dashboard"

            setOnClickListener {

                val intent = Intent(
                    this@OverlayTrackerService,
                    MainActivity::class.java
                ).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

                startActivity(intent)
            }
        }

        val closeButton = Button(this).apply {

            text = "Close Panel"

            setOnClickListener {
                closePanel()
            }
        }

        container.addView(heading)
        container.addView(appNameText)
        container.addView(packageText)
        container.addView(settingsButton)
        container.addView(trackerButton)
        container.addView(closeButton)

        panel = container
    }

    private fun togglePanel() {

        if (isPanelOpen) {
            closePanel()
        } else {
            openPanel()
        }
    }

    private fun openPanel() {

        if (panel == null) {
            createPanel()
        }

        val params = WindowManager.LayoutParams(
            300,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.CENTER
        }

        try {

            windowManager.addView(panel, params)

            isPanelOpen = true

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closePanel() {

        if (panel != null && isPanelOpen) {

            try {
                windowManager.removeView(panel)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        isPanelOpen = false
    }

    // Update detected application.
    private fun updateDetectedApp() {

        val app = findForegroundApp()

        val name = app.first
        val packageName = app.second

        currentPackage = packageName

        appNameText?.text = name
        packageText?.text = packageName

        bubble?.text =
            if (packageName.isBlank()) "LT" else "●"
    }

    // Detect the most recently resumed external application.
    private fun findForegroundApp(): Pair<String, String> {

        val usageManager =
            getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager

        val now = System.currentTimeMillis()

        val events = usageManager.queryEvents(
            now - 15 * 60 * 1000L,
            now
        )

        val event = UsageEvents.Event()

        var latestPackage = ""
        var latestTime = 0L

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
                event.timeStamp >= latestTime
            ) {

                latestPackage = event.packageName
                latestTime = event.timeStamp
            }
        }

        if (latestPackage.isBlank()) {
            return Pair("No application detected", "")
        }

        val label = try {

            val info = packageManager.getApplicationInfo(
                latestPackage,
                0
            )

            packageManager.getApplicationLabel(info).toString()

        } catch (e: Exception) {

            latestPackage
        }

        return Pair(label, latestPackage)
    }

    override fun onDestroy() {

        handler.removeCallbacks(updateTask)

        closePanel()

        bubble?.let {

            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        bubble = null
        bubbleParams = null
        panel = null

        if (Build.VERSION.SDK_INT >= 24) {

            stopForeground(STOP_FOREGROUND_REMOVE)

        } else {

            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        private const val CHANNEL_ID =
            "live_tracker_channel"

        private const val NOTIFICATION_ID = 1001
    }
}

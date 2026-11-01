
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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayTrackerService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------
    // PREFERENCES
    // ---------------------------------------------------------

    private val servicePrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var isBubbleHidden = false

    // ---------------------------------------------------------
    // FLOATING BUBBLE
    // ---------------------------------------------------------

    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var closeTarget: FrameLayout? = null

    // ---------------------------------------------------------
    // INFORMATION PANEL
    // ---------------------------------------------------------

    private var panel: LinearLayout? = null
    private var appNameText: TextView? = null
    private var packageText: TextView? = null
    private var detectedIcon: ImageView? = null

    private var currentPackage = ""
    private var isPanelOpen = false

    // ---------------------------------------------------------
    // MONITORING
    // ---------------------------------------------------------

    private val updateTask = object : Runnable {
        override fun run() {
            updateDetectedApp()
            handler.postDelayed(this, 1500)
        }
    }

    // ---------------------------------------------------------
    // COMPANION OBJECT
    // ---------------------------------------------------------

    companion object {
        private const val CHANNEL_ID = "live_tracker_channel"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS_NAME = "live_tracker_service"
        private const val KEY_BUBBLE_HIDDEN = "bubble_hidden"
        const val KEY_TRACKER_RUNNING = "tracker_running"
    }

    // ---------------------------------------------------------
    // SERVICE LIFECYCLE
    // ---------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startTrackerForeground()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        servicePrefs.edit()
            .putBoolean(KEY_TRACKER_RUNNING, true)
            .apply()

        isBubbleHidden = servicePrefs.getBoolean(
            KEY_BUBBLE_HIDDEN,
            false
        )

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)
        ) {
            if (!isBubbleHidden) {
                createBubble()
            }

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

        when (intent?.action) {

            "com.example.adware_removal.SHOW_BUBBLE" -> {

                android.util.Log.d(
                    "LIVE_TRACKER",
                    "SHOW_BUBBLE request received"
                )

                servicePrefs.edit()
                    .putBoolean(KEY_BUBBLE_HIDDEN, false)
                    .apply()

                isBubbleHidden = false

                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    Settings.canDrawOverlays(this)
                ) {
                    if (bubble == null) {
                        createBubble()
                    }
                }
            }

            null -> {
                if (!isBubbleHidden) {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        Settings.canDrawOverlays(this)
                    ) {
                        if (bubble == null) {
                            createBubble()
                        }
                    }
                }
            }

            else -> {
                android.util.Log.d(
                    "LIVE_TRACKER",
                    "Unknown service action: ${intent.action}"
                )
            }
        }

        handler.removeCallbacks(updateTask)
        handler.post(updateTask)

        return START_STICKY
    }

    // ---------------------------------------------------------
    // NOTIFICATION
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // SCREEN AND UI HELPERS
    // ---------------------------------------------------------

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun getScreenMetrics(): DisplayMetrics {

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        return metrics
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()

            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun createActionButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        action: () -> Unit
    ): Button {

        return Button(this).apply {

            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(textColor)

            typeface = Typeface.DEFAULT_BOLD

            background = roundedBackground(
                backgroundColor,
                12
            )

            elevation = dp(2).toFloat()

            setPadding(
                dp(12),
                dp(4),
                dp(12),
                dp(4)
            )

            setOnClickListener {
                action()
            }
        }
    }

    // ---------------------------------------------------------
    // DRAG-TO-X CLOSE TARGET
    // ---------------------------------------------------------

    private fun showCloseTarget() {

        if (closeTarget != null) return

        val target = FrameLayout(this).apply {

            background = roundedBackground(
                Color.rgb(180, 35, 50),
                22
            )

            elevation = dp(12).toFloat()
        }

        val label = TextView(this).apply {

            text = "✕\nSTOP TRACKER"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        target.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            dp(108),
            dp(76),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(45)
        }

        try {

            windowManager.addView(target, params)
            closeTarget = target

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCloseTargetState(active: Boolean) {

        closeTarget?.background = roundedBackground(
            if (active) {
                Color.rgb(255, 35, 55)
            } else {
                Color.rgb(180, 35, 50)
            },
            22,
            if (active) Color.WHITE else Color.TRANSPARENT
        )
    }

private fun isPointerOverCloseTarget(
    rawX: Float,
    rawY: Float
): Boolean {

    val target = closeTarget ?: return false

    val width = if (target.width > 0) target.width else dp(108)
    val height = if (target.height > 0) target.height else dp(76)

    val left: Int
    val top: Int

    if (target.width > 0 && target.height > 0) {
        // Use the actual position after the target has been laid out.
        val location = IntArray(2)
        target.getLocationOnScreen(location)

        left = location[0]
        top = location[1]
    } else {
        // Fallback if the user moves the bubble before layout completes.
        val metrics = getScreenMetrics()

        left = (metrics.widthPixels - width) / 2
        top = metrics.heightPixels - height - dp(45)
    }

    val inside =
        rawX >= left &&
        rawX <= left + width &&
        rawY >= top &&
        rawY <= top + height

    android.util.Log.d(
        "LIVE_TRACKER",
        "Close target: inside=$inside, x=$rawX, y=$rawY"
    )

    return inside
}

    private fun hideCloseTarget() {

        closeTarget?.let {

            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        closeTarget = null
    }

    // ---------------------------------------------------------
    // STOP ENTIRE TRACKER
    // ---------------------------------------------------------
private fun stopTrackerFromBubble() {

    android.util.Log.d(
        "LIVE_TRACKER",
        "X target activated. Stopping entire tracker."
    )

    // Immediately update the shared tracker status.
    servicePrefs.edit()
        .putBoolean(KEY_BUBBLE_HIDDEN, true)
        .putBoolean(KEY_TRACKER_RUNNING, false)
        .apply()

    // Stop the foreground-app monitoring loop.
    handler.removeCallbacks(updateTask)

    // Remove the foreground notification.
    stopForeground(STOP_FOREGROUND_REMOVE)

    // Destroy the service and clean up its floating views.
    stopSelf()
}

    // ---------------------------------------------------------
    // FLOATING BUBBLE WITH REAL APPLICATION ICON
    // ---------------------------------------------------------

    private fun createBubble() {

        if (bubble != null) return

        val bubbleSize = dp(62)
        val edgeMargin = dp(16)

        val metrics = getScreenMetrics()

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or Gravity.LEFT

            x = (
                metrics.widthPixels -
                    bubbleSize -
                    edgeMargin
            ).coerceAtLeast(0)

            y = (
                (metrics.heightPixels - bubbleSize) / 2
            ).coerceAtLeast(0)
        }

        bubbleParams = params

        val bubbleContainer = FrameLayout(this).apply {

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(25, 105, 210))
                setStroke(dp(2), Color.WHITE)
            }

            elevation = dp(10).toFloat()
            clipToOutline = true
        }

        val appIcon = ImageView(this).apply {

            scaleType = ImageView.ScaleType.FIT_CENTER

            setPadding(
                dp(9),
                dp(9),
                dp(9),
                dp(9)
            )

            try {
                setImageDrawable(
                    packageManager.getApplicationIcon(packageName)
                )
            } catch (e: Exception) {
                setImageResource(
                    android.R.drawable.ic_menu_view
                )
            }

            contentDescription = "Live UI Tracker"
        }

        bubbleContainer.addView(
            appIcon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val statusDot = View(this).apply {

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(40, 220, 120))
                setStroke(dp(2), Color.WHITE)
            }
        }

        bubbleContainer.addView(
            statusDot,
            FrameLayout.LayoutParams(
                dp(14),
                dp(14),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(1)
                bottomMargin = dp(1)
            }
        )

        // -----------------------------------------------------
        // BUBBLE TOUCH AND DRAG HANDLING
        // -----------------------------------------------------

        val touchSlop =
            ViewConfiguration.get(this).scaledTouchSlop

        var startX = 0
        var startY = 0

        var downRawX = 0f
        var downRawY = 0f

        var isDragging = false
        var overCloseTarget = false

        bubbleContainer.setOnTouchListener { view, event ->

            val currentParams =
                bubbleParams ?: return@setOnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    startX = currentParams.x
                    startY = currentParams.y

                    downRawX = event.rawX
                    downRawY = event.rawY

                    isDragging = false
                    overCloseTarget = false

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

                        showCloseTarget()
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

                        currentParams.x = (
                            startX + deltaX.toInt()
                        ).coerceIn(0, maxX)

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

                        val currentlyOver =
                            isPointerOverCloseTarget(
                                event.rawX,
                                event.rawY
                            )

                        if (currentlyOver != overCloseTarget) {

                            overCloseTarget = currentlyOver

                            updateCloseTargetState(
                                overCloseTarget
                            )
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (isDragging) {

                        val shouldStop =
                            isPointerOverCloseTarget(
                                event.rawX,
                                event.rawY
                            )

                        if (shouldStop) {

                            stopTrackerFromBubble()

                        } else {

                            hideCloseTarget()
                        }

                    } else {

                        view.performClick()
                    }

                    isDragging = false
                    overCloseTarget = false

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    hideCloseTarget()

                    isDragging = false
                    overCloseTarget = false

                    true
                }

                else -> false
            }
        }

        bubbleContainer.setOnClickListener {
            togglePanel()
        }

        bubble = bubbleContainer

        try {

            windowManager.addView(
                bubbleContainer,
                params
            )

        } catch (e: Exception) {

            bubble = null
            bubbleParams = null

            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------
    // INFORMATION PANEL
    // ---------------------------------------------------------

    private fun createPanel() {

        val container = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
            )

            background = roundedBackground(
                Color.rgb(20, 25, 37),
                22,
                Color.rgb(55, 68, 88)
            )

            elevation = dp(18).toFloat()
        }

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleContainer = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
        }

        val heading = createText(
            "LIVE UI TRACKER",
            14f,
            Color.rgb(110, 175, 255),
            true
        )

        val subtitle = createText(
            "Foreground application monitor",
            11f,
            Color.LTGRAY
        )

        titleContainer.addView(heading)
        titleContainer.addView(subtitle)

        header.addView(
            titleContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val closeHeader = createText(
            "✕",
            20f,
            Color.WHITE,
            true
        ).apply {

            gravity = Gravity.CENTER

            background = roundedBackground(
                Color.rgb(48, 56, 72),
                12
            )

            setPadding(
                dp(12),
                dp(7),
                dp(12),
                dp(7)
            )

            setOnClickListener {
                closePanel()
            }
        }

        header.addView(closeHeader)

        container.addView(header)

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(55, 65, 82))
        }

        container.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            }
        )

        val appCard = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                dp(12),
                dp(14),
                dp(12),
                dp(14)
            )

            background = roundedBackground(
                Color.rgb(31, 39, 55),
                16
            )
        }

        detectedIcon = ImageView(this).apply {

            scaleType = ImageView.ScaleType.FIT_CENTER

            background = roundedBackground(
                Color.rgb(47, 58, 78),
                12
            )

            setPadding(dp(7), dp(7), dp(7), dp(7))

            setImageResource(
                android.R.drawable.ic_menu_view
            )
        }

        appCard.addView(
            detectedIcon,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        val appInfoContainer = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(12),
                0,
                0,
                0
            )
        }

        val appLabel = createText(
            "CURRENT APPLICATION",
            10f,
            Color.rgb(120, 170, 235),
            true
        )

        appNameText = createText(
            "Detecting application...",
            16f,
            Color.WHITE,
            true
        ).apply {
            setPadding(0, dp(4), 0, dp(4))
        }

        packageText = createText(
            "Waiting for application",
            10f,
            Color.LTGRAY
        )

        appInfoContainer.addView(appLabel)
        appInfoContainer.addView(appNameText)
        appInfoContainer.addView(packageText)

        appCard.addView(
            appInfoContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        container.addView(appCard)

        val settingsButton = createActionButton(
            "Open App Info",
            Color.rgb(40, 105, 205),
            Color.WHITE
        ) {
            openCurrentAppInfo()
        }

        container.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(16)
            }
        )

        val dashboardButton = createActionButton(
            "Open Tracker Dashboard",
            Color.rgb(40, 49, 67),
            Color.WHITE
        ) {
            openDashboard()
        }

        container.addView(
            dashboardButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(9)
            }
        )

        val closeButton = createActionButton(
            "Close Panel",
            Color.rgb(55, 43, 52),
            Color.rgb(255, 150, 165)
        ) {
            closePanel()
        }

        container.addView(
            closeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(9)
            }
        )

        panel = container
    }

    // ---------------------------------------------------------
    // PANEL ACTIONS
    // ---------------------------------------------------------

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

        val panelView = panel ?: return

        val params = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.CENTER
        }

        try {

            updateDetectedApp()

            windowManager.addView(
                panelView,
                params
            )

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

    private fun openCurrentAppInfo() {

        if (currentPackage.isBlank()) {
            return
        }

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

    private fun openDashboard() {

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

    // ---------------------------------------------------------
    // FOREGROUND APPLICATION DETECTION
    // ---------------------------------------------------------

    private fun updateDetectedApp() {

        val app = findForegroundApp()

        val name = app.first
        val packageName = app.second

        currentPackage = packageName

        appNameText?.text = name

        packageText?.text =
            if (packageName.isBlank()) {
                "No package detected"
            } else {
                packageName
            }

        if (packageName.isNotBlank()) {

            try {

                val icon = packageManager.getApplicationIcon(
                    packageName
                )

                detectedIcon?.setImageDrawable(icon)

            } catch (e: Exception) {

                detectedIcon?.setImageResource(
                    android.R.drawable.ic_menu_view
                )
            }

        } else {

            detectedIcon?.setImageResource(
                android.R.drawable.ic_menu_view
            )
        }
    }

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
                event.packageName != this.packageName &&
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

    // ---------------------------------------------------------
    // SERVICE CLEANUP
    // ---------------------------------------------------------

    override fun onDestroy() {

        android.util.Log.d(
            "LIVE_TRACKER",
            "Tracker service destroyed. Cleaning up."
        )

        handler.removeCallbacks(updateTask)

        closePanel()
        hideCloseTarget()

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

        // Tell Flutter that the tracker is no longer running.
        servicePrefs.edit()
            .putBoolean(KEY_TRACKER_RUNNING, false)
            .apply()

        // Remove the foreground notification.
        if (Build.VERSION.SDK_INT >= 24) {

            stopForeground(STOP_FOREGROUND_REMOVE)

        } else {

            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

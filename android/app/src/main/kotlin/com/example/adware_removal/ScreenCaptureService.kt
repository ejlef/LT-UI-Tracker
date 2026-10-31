
package com.example.adware_removal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        if (projection != null) {
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", 0)
            ?: return START_NOT_STICKY

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                "data",
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>("data")
        } ?: return START_NOT_STICKY

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Live UI Tracker")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            startProjection()
        } catch (e: Exception) {
            e.printStackTrace()
            stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun startProjection() {
       val metrics = DisplayMetrics()

val wm = getSystemService(
    Context.WINDOW_SERVICE
) as WindowManager

@Suppress("DEPRECATION")
wm.defaultDisplay.getRealMetrics(metrics)

val width = metrics.widthPixels
val height = metrics.heightPixels
val density = metrics.densityDpi

        workerThread = HandlerThread("LiveTrackerCapture").also {
            it.start()
        }
        workerHandler = Handler(workerThread!!.looper)

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()

            if (now - lastFrameTime < 1000) {
                val unused = reader.acquireLatestImage()
                unused?.close()
                return@setOnImageAvailableListener
            }

            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )

                buffer.rewind()
                fullBitmap.copyPixelsFromBuffer(buffer)

                val croppedBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    0,
                    0,
                    width,
                    height
                )

                val output = ByteArrayOutputStream()
                croppedBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    55,
                    output
                )

                val broadcast = Intent(ACTION_FRAME).apply {
                    setPackage(packageName)
                    putExtra("bytes", output.toByteArray())
                }

                sendBroadcast(broadcast)

                output.close()
                croppedBitmap.recycle()
                fullBitmap.recycle()

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }

        }, workerHandler)

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            },
            workerHandler
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "Live UI Tracker",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            projection?.stop()
            projection = null

            workerThread?.quitSafely()
            workerThread = null
            workerHandler = null

        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (projection != null) {
            virtualDisplay?.release()
            imageReader?.close()
            projection?.stop()
        }

        workerThread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_FRAME =
            "com.example.adware_removal.SCREEN_FRAME"

        const val ACTION_STOP =
            "com.example.adware_removal.STOP_CAPTURE"

        private const val CHANNEL_ID = "live_tracker_capture"
        private const val NOTIFICATION_ID = 1002
    }
}

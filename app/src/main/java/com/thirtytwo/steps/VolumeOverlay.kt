package com.thirtytwo.steps

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class VolumeOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: android.view.View? = null
    private var volumeFill: FrameLayout? = null
    private var hideRunnable: Runnable? = null
    private var isShowing = false

    // Total height of the overlay in pixels
    private val overlayHeightDp = 180
    private val overlayHeightPx: Int
        get() = (overlayHeightDp * context.resources.displayMetrics.density).toInt()

    private val layoutParams: WindowManager.LayoutParams
        get() {
            val widthDp = 42
            val widthPx = (widthDp * context.resources.displayMetrics.density).toInt()
            val heightPx = overlayHeightPx

            return WindowManager.LayoutParams(
                widthPx,
                heightPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = 16
            }
        }

    fun show(currentStep: Int, totalSteps: Int) {
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }

            if (!isShowing) {
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.overlay_volume, null)
                volumeFill = overlayView?.findViewById(R.id.volume_fill)

                try {
                    windowManager.addView(overlayView, layoutParams)
                    isShowing = true
                } catch (_: Exception) {
                    return@post
                }
            }

            // Set fill height based on volume percentage
            val fraction = if (totalSteps > 0) currentStep.toFloat() / totalSteps else 0f
            val fillHeight = (overlayHeightPx * fraction).toInt()
            volumeFill?.layoutParams?.height = fillHeight
            volumeFill?.requestLayout()

            hideRunnable = Runnable { hide() }
            handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
        }
    }

    fun hide() {
        handler.post {
            if (isShowing) {
                try {
                    windowManager.removeView(overlayView)
                } catch (_: Exception) {}
                overlayView = null
                volumeFill = null
                isShowing = false
            }
        }
    }

    companion object {
        private const val SHOW_DURATION_MS = 1500L
    }
}

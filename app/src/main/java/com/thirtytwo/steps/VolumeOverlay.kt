package com.thirtytwo.steps

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView

class VolumeOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: android.view.View? = null
    private var seekBar: SeekBar? = null
    private var stepText: TextView? = null
    private var hideRunnable: Runnable? = null
    private var isShowing = false
    private var isDragging = false

    var onSeekChanged: ((Int) -> Unit)? = null

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.85).toInt()

        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * displayMetrics.density).toInt()
        }
    }

    fun show(currentStep: Int, totalSteps: Int) {
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }

            if (!isShowing) {
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.overlay_volume, null)
                seekBar = overlayView?.findViewById(R.id.step_progress)
                stepText = overlayView?.findViewById(R.id.step_text)

                seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            stepText?.text = "$progress/$totalSteps"
                            onSeekChanged?.invoke(progress)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        isDragging = true
                        // Cancel hide while dragging
                        hideRunnable?.let { handler.removeCallbacks(it) }
                    }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        isDragging = false
                        // Resume hide timer
                        hideRunnable = Runnable { hide() }
                        handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
                    }
                })

                try {
                    windowManager.addView(overlayView, buildLayoutParams())
                    isShowing = true
                } catch (_: Exception) {
                    return@post
                }
            }

            if (!isDragging) {
                seekBar?.max = totalSteps
                seekBar?.progress = currentStep
                stepText?.text = "$currentStep/$totalSteps"
            }

            if (!isDragging) {
                hideRunnable = Runnable { hide() }
                handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
            }
        }
    }

    fun hide() {
        handler.post {
            if (isDragging) return@post
            if (isShowing) {
                try {
                    windowManager.removeView(overlayView)
                } catch (_: Exception) {}
                overlayView = null
                seekBar = null
                stepText = null
                isShowing = false
            }
        }
    }

    companion object {
        private const val SHOW_DURATION_MS = 1500L
    }
}

package com.thirtytwo.steps

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

class VolumeOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var seekBar: SeekBar? = null
    private var stepText: TextView? = null
    private var expandBtn: ImageView? = null
    private var extraSliders: View? = null
    private var hideRunnable: Runnable? = null
    private var isShowing = false
    private var isDragging = false
    private var isExpanded = false

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
                expandBtn = overlayView?.findViewById(R.id.btn_expand)
                extraSliders = overlayView?.findViewById(R.id.extra_sliders)

                setupMediaSeekbar(totalSteps)
                setupExpandButton()
                setupStreamSliders()

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

            if (!isDragging && !isExpanded) {
                hideRunnable = Runnable { hide() }
                handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
            }
        }
    }

    private fun setupMediaSeekbar(totalSteps: Int) {
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    stepText?.text = "$progress/$totalSteps"
                    onSeekChanged?.invoke(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
                hideRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                if (!isExpanded) {
                    hideRunnable = Runnable { hide() }
                    handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
                }
            }
        })
    }

    private fun setupExpandButton() {
        expandBtn?.setOnClickListener {
            isExpanded = !isExpanded
            extraSliders?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            expandBtn?.setImageResource(
                if (isExpanded) R.drawable.ic_collapse else R.drawable.ic_expand
            )

            hideRunnable?.let { handler.removeCallbacks(it) }
            if (!isExpanded) {
                hideRunnable = Runnable { hide() }
                handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
            }

            if (isExpanded) refreshStreamSliders()
        }
    }

    private fun setupStreamSliders() {
        setupStreamSlider(R.id.ring_slider, AudioManager.STREAM_RING)
        setupStreamSlider(R.id.notification_slider, AudioManager.STREAM_NOTIFICATION)
        setupStreamSlider(R.id.alarm_slider, AudioManager.STREAM_ALARM)
    }

    private fun setupStreamSlider(id: Int, stream: Int) {
        val slider = overlayView?.findViewById<SeekBar>(id) ?: return
        slider.max = audioManager.getStreamMaxVolume(stream)
        slider.progress = audioManager.getStreamVolume(stream)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(stream, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun refreshStreamSliders() {
        overlayView?.findViewById<SeekBar>(R.id.ring_slider)?.apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        }
        overlayView?.findViewById<SeekBar>(R.id.notification_slider)?.apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        }
        overlayView?.findViewById<SeekBar>(R.id.alarm_slider)?.apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
    }

    fun hide() {
        handler.post {
            if (isDragging || isExpanded) return@post
            if (isShowing) {
                try {
                    windowManager.removeView(overlayView)
                } catch (_: Exception) {}
                overlayView = null
                seekBar = null
                stepText = null
                expandBtn = null
                extraSliders = null
                isShowing = false
                isExpanded = false
            }
        }
    }

    companion object {
        private const val SHOW_DURATION_MS = 1500L
    }
}

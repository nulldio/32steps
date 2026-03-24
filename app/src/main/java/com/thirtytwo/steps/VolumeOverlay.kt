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
    var onStreamChanged: (() -> Unit)? = null

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

    private var mediaLabel: android.widget.TextView? = null

    fun show(currentStep: Int, totalSteps: Int, streamLabel: String = "Media") {
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }

            if (!isShowing) {
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.overlay_volume, null)
                seekBar = overlayView?.findViewById(R.id.step_progress)
                stepText = overlayView?.findViewById(R.id.step_text)
                mediaLabel = overlayView?.findViewById(R.id.stream_label)
                expandBtn = overlayView?.findViewById(R.id.btn_expand)
                extraSliders = overlayView?.findViewById(R.id.extra_sliders)

                setupMediaSeekbar(totalSteps)
                setupRingerToggle()
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
                mediaLabel?.text = streamLabel
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

    private fun setupRingerToggle() {
        val ringerBtn = overlayView?.findViewById<ImageView>(R.id.btn_ringer) ?: return
        updateRingerIcon(ringerBtn)

        ringerBtn.setOnClickListener {
            try {
                val current = audioManager.ringerMode
                val next = when (current) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                audioManager.ringerMode = next
                updateRingerIcon(ringerBtn)
            } catch (_: Exception) {}

            // Reset hide timer
            hideRunnable?.let { handler.removeCallbacks(it) }
            if (!isExpanded) {
                hideRunnable = Runnable { hide() }
                handler.postDelayed(hideRunnable!!, SHOW_DURATION_MS)
            }
        }
    }

    private fun updateRingerIcon(btn: ImageView) {
        val icon = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> R.drawable.ic_ring
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_vibrate
            else -> R.drawable.ic_silent
        }
        btn.setImageResource(icon)
    }

    private fun setupExpandButton() {
        expandBtn?.setOnClickListener {
            // Check DND access before expanding
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                // Can't open settings from overlay easily, just don't expand
                return@setOnClickListener
            }
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
        setupStreamSlider(R.id.ring_slider, R.id.ring_counter, AudioManager.STREAM_RING)
        setupStreamSlider(R.id.notification_slider, R.id.notif_counter, AudioManager.STREAM_NOTIFICATION)
        setupStreamSlider(R.id.alarm_slider, R.id.alarm_counter, AudioManager.STREAM_ALARM)
        setupStreamSlider(R.id.call_slider, R.id.call_counter, AudioManager.STREAM_VOICE_CALL)
    }

    private fun setupStreamSlider(sliderId: Int, counterId: Int, stream: Int) {
        val slider = overlayView?.findViewById<SeekBar>(sliderId) ?: return
        val counter = overlayView?.findViewById<TextView>(counterId)
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        slider.max = max
        slider.progress = current
        counter?.text = "$current/$max"

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    try { audioManager.setStreamVolume(stream, progress, 0) } catch (_: Exception) {}
                    counter?.text = "$progress/$max"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onStreamChanged?.invoke()
            }
        })
    }

    private fun refreshStreamSliders() {
        refreshOneSlider(R.id.ring_slider, R.id.ring_counter, AudioManager.STREAM_RING)
        refreshOneSlider(R.id.notification_slider, R.id.notif_counter, AudioManager.STREAM_NOTIFICATION)
        refreshOneSlider(R.id.alarm_slider, R.id.alarm_counter, AudioManager.STREAM_ALARM)
        refreshOneSlider(R.id.call_slider, R.id.call_counter, AudioManager.STREAM_VOICE_CALL)
    }

    private fun refreshOneSlider(sliderId: Int, counterId: Int, stream: Int) {
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        overlayView?.findViewById<SeekBar>(sliderId)?.apply {
            this.max = max
            progress = current
        }
        overlayView?.findViewById<TextView>(counterId)?.text = "$current/$max"
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

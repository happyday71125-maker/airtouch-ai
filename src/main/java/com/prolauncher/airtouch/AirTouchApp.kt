package com.prolauncher.airtouch

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode

import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

enum class AirTouchAction(val titleKo: String) {
    NONE("동작 없음"),
    TAP_AT_CURSOR("커서 위치 클릭 (Tap)"),
    LONG_PRESS_AT_CURSOR("커서 위치 길게 누르기"),
    DOUBLE_TAP_AT_CURSOR("커서 위치 더블 클릭"),
    SCROLL_DOWN("아래로 스크롤 (다음 내용)"),
    SCROLL_UP("위로 스크롤 (이전 내용)"),
    SWIPE_LEFT("왼쪽으로 스와이프"),
    SWIPE_RIGHT("오른쪽으로 스와이프"),
    GLOBAL_BACK("뒤로 가기 (Back)"),
    GLOBAL_HOME("홈 화면 이동 (Home)"),
    GLOBAL_RECENTS("최근 앱 목록 (Recents)"),
    GLOBAL_NOTIFICATIONS("알림창 열기"),
    GLOBAL_QUICK_SETTINGS("빠른 설정창 열기"),
    GLOBAL_LOCK_SCREEN("화면 잠금 (Lock Screen)"),
    GLOBAL_SCREENSHOT("화면 캡처 (Screenshot)"),
    MEDIA_PLAY_PAUSE("미디어 재생 / 일시정지"),
    MEDIA_NEXT("다음 곡 / 영상"),
    MEDIA_PREV("이전 곡 / 영상"),
    VOLUME_UP("볼륨 높이기 (+)"),
    VOLUME_DOWN("볼륨 낮추기 (-)"),
    TOGGLE_PAUSE_TRACKING("커서 일시정지 / 재개 토글")
}

enum class HandPose(val key: String, val labelKo: String, val defaultAction: AirTouchAction) {
    PEACE("pose_peace", "✌️ 브이 (검지+중지)", AirTouchAction.GLOBAL_BACK),
    OPEN_PALM("pose_palm", "🖐️ 손바닥 펴기 (다섯 손가락)", AirTouchAction.GLOBAL_HOME),
    THREE_FINGERS("pose_three", "☝️ 세 손가락 (검지+중지+약지)", AirTouchAction.GLOBAL_RECENTS),
    ROCK_ON("pose_rock", "🤘 락앤롤 (검지+새끼)", AirTouchAction.GLOBAL_LOCK_SCREEN),
    THUMB_UP("pose_thumb_up", "👍 엄지 척 (엄지만 폄)", AirTouchAction.MEDIA_PLAY_PAUSE),
    FINGER_GUN("pose_gun", "👈 권총 모양 (엄지+검지)", AirTouchAction.VOLUME_UP),
    PINKY_UP("pose_pinky", "🤙 샤카/새끼 (엄지+새끼)", AirTouchAction.VOLUME_DOWN),
    FIST("pose_fist", "✊ 주먹 쥐기", AirTouchAction.TOGGLE_PAUSE_TRACKING)
}

class LowRamConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("airtouch_lowram_prefs", Context.MODE_PRIVATE)

    var cameraWidth: Int
        get() = prefs.getInt("camera_width", 240)
        set(value) = prefs.edit().putInt("camera_width", value).apply()

    var cameraHeight: Int
        get() = prefs.getInt("camera_height", 320)
        set(value) = prefs.edit().putInt("camera_height", value).apply()

    var activeFps: Int
        get() = prefs.getInt("active_fps", 15)
        set(value) = prefs.edit().putInt("active_fps", value.coerceIn(5, 30)).apply()

    var idleFps: Int
        get() = prefs.getInt("idle_fps", 4)
        set(value) = prefs.edit().putInt("idle_fps", value.coerceIn(1, 10)).apply()

    var useGpuDelegate: Boolean
        get() = prefs.getBoolean("use_gpu_delegate", false)
        set(value) = prefs.edit().putBoolean("use_gpu_delegate", value).apply()

    var cursorSpeed: Float
        get() = prefs.getFloat("cursor_speed", 1.35f)
        set(value) = prefs.edit().putFloat("cursor_speed", value.coerceIn(0.5f, 2.5f)).apply()

    var smoothingAlpha: Float
        get() = prefs.getFloat("smoothing_alpha", 0.35f)
        set(value) = prefs.edit().putFloat("smoothing_alpha", value.coerceIn(0.1f, 0.9f)).apply()

    var pinchThreshold: Float
        get() = prefs.getFloat("pinch_threshold", 0.052f)
        set(value) = prefs.edit().putFloat("pinch_threshold", value.coerceIn(0.025f, 0.095f)).apply()

    var poseHoldDurationMs: Long
        get() = prefs.getLong("pose_hold_ms", 600L)
        set(value) = prefs.edit().putLong("pose_hold_ms", value.coerceIn(300L, 1500L)).apply()

    var swipeScrollEnabled: Boolean
        get() = prefs.getBoolean("swipe_scroll_enabled", true)
        set(value) = prefs.edit().putBoolean("swipe_scroll_enabled", value).apply()

    fun getActionForPose(pose: HandPose): AirTouchAction {
        val savedName = prefs.getString(pose.key, pose.defaultAction.name) ?: pose.defaultAction.name
        return try {
            AirTouchAction.valueOf(savedName)
        } catch (_: Exception) {
            pose.defaultAction
        }
    }

    fun setActionForPose(pose: HandPose, action: AirTouchAction) {
        prefs.edit().putString(pose.key, action.name).apply()
    }
}

class GestureClassifier(private val config: LowRamConfig) {

    data class FrameEvaluation(
        val normCursorX: Float,
        val normCursorY: Float,
        val isPinching: Boolean,
        val pinchJustClicked: Boolean,
        val pinchLongPressed: Boolean,
        val detectedPose: HandPose?,
        val poseHoldProgress: Float,
        val triggeredPoseAction: AirTouchAction?,
        val triggeredSwipeAction: AirTouchAction?,
        val trackingPaused: Boolean
    )

    private var smoothedX = 0.5f
    private var smoothedY = 0.5f
    private var isCurrentlyPinching = false
    private var pinchStartTimeMs = 0L
    private var longPressTriggeredInCurrentPinch = false
    private var currentCandidatePose: HandPose? = null
    private var poseStartTimeMs = 0L
    private var poseTriggeredInCurrentHold = false
    private var lastPalmX = -1f
    private var lastPalmY = -1f
    private var lastPalmTimeMs = 0L
    private var lastSwipeTriggerTimeMs = 0L

    var isTrackingPaused: Boolean = false
        private set

    fun evaluate(landmarks: List<NormalizedLandmark>): FrameEvaluation {
        val now = SystemClock.uptimeMillis()
        val indexTip = landmarks[8]
        val thumbTip = landmarks[4]
        val wrist = landmarks[0]
        val middleMcp = landmarks[9]

        val rawX = 1.0f - indexTip.x()
        val rawY = indexTip.y()
        val speed = config.cursorSpeed
        val mappedX = ((rawX - 0.5f) * speed + 0.5f).coerceIn(0f, 1f)
        val mappedY = ((rawY - 0.5f) * speed + 0.5f).coerceIn(0f, 1f)

        val alpha = config.smoothingAlpha
        val dx = mappedX - smoothedX
        val dy = mappedY - smoothedY
        if (hypot(dx, dy) > 0.004f) {
            smoothedX += dx * alpha
            smoothedY += dy * alpha
        }

        val pinchDist = distance2D(thumbTip, indexTip)
        val threshold = config.pinchThreshold
        val pinchActive = if (isCurrentlyPinching) {
            pinchDist < (threshold * 1.30f)
        } else {
            pinchDist < threshold
        }

        var pinchJustClicked = false
        var pinchLongPressed = false

        if (pinchActive) {
            if (!isCurrentlyPinching) {
                isCurrentlyPinching = true
                pinchStartTimeMs = now
                longPressTriggeredInCurrentPinch = false
            } else if (!longPressTriggeredInCurrentPinch && (now - pinchStartTimeMs) >= 650L) {
                if (!isTrackingPaused) pinchLongPressed = true
                longPressTriggeredInCurrentPinch = true
            }
        } else {
            if (isCurrentlyPinching) {
                val heldMs = now - pinchStartTimeMs
                if (!longPressTriggeredInCurrentPinch && heldMs in 35L..649L && !isTrackingPaused) {
                    pinchJustClicked = true
                }
                isCurrentlyPinching = false
            }
        }

        val rawPose = classifyStaticPose(landmarks, pinchActive)
        var poseProgress = 0f
        var triggeredPoseAction: AirTouchAction? = null

        if (rawPose != null) {
            if (rawPose != currentCandidatePose) {
                currentCandidatePose = rawPose
                poseStartTimeMs = now
                poseTriggeredInCurrentHold = false
            } else {
                val elapsed = now - poseStartTimeMs
                val holdRequired = config.poseHoldDurationMs
                poseProgress = (elapsed.toFloat() / holdRequired.toFloat()).coerceIn(0f, 1f)
                if (!poseTriggeredInCurrentHold && elapsed >= holdRequired) {
                    poseTriggeredInCurrentHold = true
                    val mappedAction = config.getActionForPose(rawPose)
                    if (mappedAction == AirTouchAction.TOGGLE_PAUSE_TRACKING) {
                        isTrackingPaused = !isTrackingPaused
                        triggeredPoseAction = mappedAction
                    } else if (!isTrackingPaused) {
                        triggeredPoseAction = mappedAction
                    }
                }
            }
        } else {
            currentCandidatePose = null
            poseProgress = 0f
            poseTriggeredInCurrentHold = false
        }

        var triggeredSwipeAction: AirTouchAction? = null
        val palmX = 1.0f - ((wrist.x() + middleMcp.x()) * 0.5f)
        val palmY = (wrist.y() + middleMcp.y()) * 0.5f

        if (config.swipeScrollEnabled && !isTrackingPaused && !pinchActive) {
            val dt = now - lastPalmTimeMs
            if (lastPalmX >= 0f && dt in 40L..220L && (now - lastSwipeTriggerTimeMs) > 650L) {
                val vx = (palmX - lastPalmX) / (dt / 1000f)
                val vy = (palmY - lastPalmY) / (dt / 1000f)
                val speedThreshold = 1.45f
                if (abs(vy) > abs(vx) && abs(vy) > speedThreshold) {
                    triggeredSwipeAction = if (vy < 0) AirTouchAction.SCROLL_DOWN else AirTouchAction.SCROLL_UP
                    lastSwipeTriggerTimeMs = now
                } else if (abs(vx) > abs(vy) && abs(vx) > speedThreshold) {
                    triggeredSwipeAction = if (vx < 0) AirTouchAction.SWIPE_LEFT else AirTouchAction.SWIPE_RIGHT
                    lastSwipeTriggerTimeMs = now
                }
            }
        }
        lastPalmX = palmX
        lastPalmY = palmY
        lastPalmTimeMs = now

        return FrameEvaluation(
            normCursorX = smoothedX,
            normCursorY = smoothedY,
            isPinching = isCurrentlyPinching,
            pinchJustClicked = pinchJustClicked,
            pinchLongPressed = pinchLongPressed,
            detectedPose = currentCandidatePose,
            poseHoldProgress = poseProgress,
            triggeredPoseAction = triggeredPoseAction,
            triggeredSwipeAction = triggeredSwipeAction,
            trackingPaused = isTrackingPaused
        )
    }

    private fun classifyStaticPose(lm: List<NormalizedLandmark>, pinchActive: Boolean): HandPose? {
        if (pinchActive) return null
        val wrist = lm[0]
        val indexUp = isFingerExtended(wrist, lm[6], lm[8])
        val middleUp = isFingerExtended(wrist, lm[10], lm[12])
        val ringUp = isFingerExtended(wrist, lm[14], lm[16])
        val pinkyUp = isFingerExtended(wrist, lm[18], lm[20])
        val thumbUp = isThumbExtended(wrist, lm[2], lm[4], lm[5], lm[17])

        return when {
            indexUp && middleUp && !ringUp && !pinkyUp -> HandPose.PEACE
            indexUp && middleUp && ringUp && !pinkyUp -> HandPose.THREE_FINGERS
            indexUp && middleUp && ringUp && pinkyUp && thumbUp -> HandPose.OPEN_PALM
            indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.ROCK_ON
            thumbUp && indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.FINGER_GUN
            thumbUp && !indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.PINKY_UP
            thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.THUMB_UP
            !thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.FIST
            else -> null
        }
    }

    private fun isFingerExtended(wrist: NormalizedLandmark, pip: NormalizedLandmark, tip: NormalizedLandmark): Boolean {
        return distance2D(wrist, tip) > (distance2D(wrist, pip) * 1.18f)
    }

    private fun isThumbExtended(
        wrist: NormalizedLandmark,
        thumbMcp: NormalizedLandmark,
        thumbTip: NormalizedLandmark,
        indexMcp: NormalizedLandmark,
        pinkyMcp: NormalizedLandmark
    ): Boolean {
        val palmWidth = distance2D(indexMcp, pinkyMcp).coerceAtLeast(0.05f)
        return (distance2D(thumbTip, indexMcp) > palmWidth * 0.65f) &&
            (distance2D(thumbTip, wrist) > distance2D(thumbMcp, wrist) * 1.15f)
    }

    private fun distance2D(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        return hypot(a.x() - b.x(), a.y() - b.y())
    }
}

class CursorOverlayView(context: Context) : View(context) {
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#00E676")
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00B0FF")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val arcRect = RectF()

    var cursorX: Float = 540f
    var cursorY: Float = 960f
    var isPinching: Boolean = false
    var isPaused: Boolean = false
    var poseProgress: Float = 0f
    var statusLabel: String? = null

    fun updateState(x: Float, y: Float, pinching: Boolean, paused: Boolean, progress: Float, label: String?) {
        cursorX = x
        cursorY = y
        isPinching = pinching
        isPaused = paused
        poseProgress = progress
        statusLabel = label
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = if (isPinching) 22f else 32f
        outerRingPaint.color = when {
            isPaused -> Color.parseColor("#FF5252")
            isPinching -> Color.parseColor("#FFD740")
            else -> Color.parseColor("#00E676")
        }
        canvas.drawCircle(cursorX, cursorY, radius, outerRingPaint)
        canvas.drawCircle(cursorX, cursorY, if (isPinching) 10f else 6f, centerDotPaint)
        if (poseProgress > 0.05f) {
            arcRect.set(cursorX - radius - 10f, cursorY - radius - 10f, cursorX + radius + 10f, cursorY + radius + 10f)
            canvas.drawArc(arcRect, -90f, 360f * poseProgress, false, progressArcPaint)
        }
        statusLabel?.let { text ->
            canvas.drawText(text, cursorX + radius + 16f, cursorY + 8f, labelPaint)
        }
    }
}

class AirTouchAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AirTouchAccessibilityService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var cursorView: CursorOverlayView? = null
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        hideCursorOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun updateScreenMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
    }

    fun showCursorOverlay() {
        if (cursorView != null) return
        val wm = windowManager ?: return
        updateScreenMetrics()
        val view = CursorOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(view, params)
            cursorView = view
        } catch (_: Exception) {}
    }

    fun hideCursorOverlay() {
        val view = cursorView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        } finally {
            cursorView = null
        }
    }

    fun updateCursorFromEvaluation(eval: GestureClassifier.FrameEvaluation) {
        if (cursorView == null) showCursorOverlay()
        val px = (eval.normCursorX * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val py = (eval.normCursorY * screenHeight).coerceIn(0f, screenHeight.toFloat())
        val label = when {
            eval.trackingPaused -> "일시정지됨 (주먹 쥐어 해제)"
            eval.detectedPose != null && eval.poseHoldProgress > 0.1f -> eval.detectedPose.labelKo
            else -> null
        }
        cursorView?.updateState(px, py, eval.isPinching, eval.trackingPaused, eval.poseHoldProgress, label)
        if (eval.pinchJustClicked) {
            performAction(AirTouchAction.TAP_AT_CURSOR, px, py)
        } else if (eval.pinchLongPressed) {
            performAction(AirTouchAction.LONG_PRESS_AT_CURSOR, px, py)
        }
        eval.triggeredPoseAction?.let { action ->
            if (action != AirTouchAction.TOGGLE_PAUSE_TRACKING) performAction(action, px, py)
        }
        eval.triggeredSwipeAction?.let { swipeAction ->
            performAction(swipeAction, px, py)
        }
    }

    fun performAction(action: AirTouchAction, x: Float = screenWidth / 2f, y: Float = screenHeight / 2f) {
        when (action) {
            AirTouchAction.NONE, AirTouchAction.TOGGLE_PAUSE_TRACKING -> Unit
            AirTouchAction.TAP_AT_CURSOR -> dispatchTap(x, y, 45L)
            AirTouchAction.LONG_PRESS_AT_CURSOR -> dispatchTap(x, y, 550L)
            AirTouchAction.DOUBLE_TAP_AT_CURSOR -> {
                dispatchTap(x, y, 40L)
                cursorView?.postDelayed({ dispatchTap(x, y, 40L) }, 110L)
            }
            AirTouchAction.SCROLL_DOWN -> dispatchSwipe(screenWidth * 0.5f, screenHeight * 0.72f, screenWidth * 0.5f, screenHeight * 0.28f, 260L)
            AirTouchAction.SCROLL_UP -> dispatchSwipe(screenWidth * 0.5f, screenHeight * 0.28f, screenWidth * 0.5f, screenHeight * 0.72f, 260L)
            AirTouchAction.SWIPE_LEFT -> dispatchSwipe(screenWidth * 0.82f, screenHeight * 0.5f, screenWidth * 0.18f, screenHeight * 0.5f, 240L)
            AirTouchAction.SWIPE_RIGHT -> dispatchSwipe(screenWidth * 0.18f, screenHeight * 0.5f, screenWidth * 0.82f, screenHeight * 0.5f, 240L)
            AirTouchAction.GLOBAL_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            AirTouchAction.GLOBAL_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            AirTouchAction.GLOBAL_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            AirTouchAction.GLOBAL_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            AirTouchAction.GLOBAL_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            AirTouchAction.GLOBAL_LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            AirTouchAction.GLOBAL_SCREENSHOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            AirTouchAction.MEDIA_PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            AirTouchAction.MEDIA_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            AirTouchAction.MEDIA_PREV -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            AirTouchAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            AirTouchAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
        }
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }
}

class AirTouchTrackingService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.prolauncher.airtouch.ACTION_START"
        const val ACTION_STOP = "com.prolauncher.airtouch.ACTION_STOP"
        private const val CHANNEL_ID = "airtouch_tracking_channel"
        private const val NOTIFICATION_ID = 4102

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var config: LowRamConfig
    private lateinit var classifier: GestureClassifier
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var handLandmarker: HandLandmarker? = null
    private var reusableBitmap: Bitmap? = null
    private val rotationMatrix = Matrix()

    @Volatile
    private var lastFrameProcessTimeMs = 0L
    @Volatile
    private var lastHandDetectedTimeMs = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseCameraPipeline()
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> resumeCameraPipeline()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = LowRamConfig(this)
        classifier = GestureClassifier(config)
        cameraExecutor = Executors.newSingleThreadExecutor()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        lastHandDetectedTimeMs = SystemClock.uptimeMillis()
        initHandLandmarker()
        startCameraPipeline()
        AirTouchAccessibilityService.instance?.showCursorOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        AirTouchAccessibilityService.instance?.hideCursorOverlay()
        releaseCameraAndModel()
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
        super.onDestroy()
    }

    private fun initHandLandmarker() {
        if (handLandmarker != null) return
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setDelegate(if (config.useGpuDelegate) Delegate.GPU else Delegate.CPU)

            val customModelFile = File(filesDir, "hand_landmarker.task")
            if (customModelFile.exists() && customModelFile.length() > 100_000L) {
                FileInputStream(customModelFile).use { fis ->
                    val mapped = fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, customModelFile.length())
                    baseOptionsBuilder.setModelAssetBuffer(mapped)
                }
            } else {
                baseOptionsBuilder.setModelAssetPath("hand_landmarker.task")
            }

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onHandLandmarkResult)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCameraPipeline() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            bindAnalysisUseCase(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalysisUseCase(provider: ProcessCameraProvider) {
        val executor = cameraExecutor ?: return
        try {
            provider.unbindAll()
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(config.cameraWidth, config.cameraHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(executor, this::processCameraFrame)
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }
        val isIdle = (now - lastHandDetectedTimeMs) > 3000L
        val targetFps = if (isIdle) config.idleFps else config.activeFps
        val minIntervalMs = 1000L / targetFps.coerceAtLeast(1)
        if ((now - lastFrameProcessTimeMs) < minIntervalMs) {
            imageProxy.close()
            return
        }
        lastFrameProcessTimeMs = now

        try {
            val width = imageProxy.width
            val height = imageProxy.height
val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            var bmp = reusableBitmap
            if (bmp == null || bmp.width != width || bmp.height != height) {
                bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                reusableBitmap = bmp
            }
            imageProxy.planes[0].buffer.rewind()
            bmp.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            val finalBitmap = if (rotationDegrees != 0) {
                rotationMatrix.reset()
                rotationMatrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bmp, 0, 0, width, height, rotationMatrix, false)
            } else {
                bmp
            }
            val mpImage = BitmapImageBuilder(finalBitmap).build()
            landmarker.detectAsync(mpImage, now)
        } catch (_: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    private fun onHandLandmarkResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        val hands = result.landmarks()
        if (hands.isNullOrEmpty()) return
        lastHandDetectedTimeMs = SystemClock.uptimeMillis()
        val evaluation = classifier.evaluate(hands[0])
        AirTouchAccessibilityService.instance?.updateCursorFromEvaluation(evaluation)
    }

    private fun pauseCameraPipeline() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
    }

    private fun resumeCameraPipeline() {
        val provider = cameraProvider ?: return
        bindAnalysisUseCase(provider)
    }

    private fun releaseCameraAndModel() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (_: Exception) {}
        try {
            handLandmarker?.close()
            handLandmarker = null
        } catch (_: Exception) {}
        reusableBitmap?.recycle()
        reusableBitmap = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AirTouch 백그라운드 추적", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, AirTouchTrackingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AirTouch AI 실행 중 (저메모리 모드)")
            .setContentText("전면 카메라 제스처 제어 활성 · 터치하여 설정 또는 종료")
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "즉시 끄기 (RAM 반환)", stopPending)
            .setOngoing(true)
            .build()
    }
}

class AirTouchTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        LauncherAirTouchBridge.toggle(this)
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        tile.state = if (AirTouchTrackingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (AirTouchTrackingService.isRunning) "AirTouch ON" else "AirTouch OFF"
        tile.updateTile()
    }
}

object LauncherAirTouchBridge {
    fun isRunning(): Boolean = AirTouchTrackingService.isRunning

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (AirTouchAccessibilityService.instance != null) return true
        val expected = "${context.packageName}/${AirTouchAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expected)
    }

    fun start(context: Context) {
        val intent = Intent(context, AirTouchTrackingService::class.java).apply {
            action = AirTouchTrackingService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, AirTouchTrackingService::class.java).apply {
            action = AirTouchTrackingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggle(context: Context): Boolean {
        return if (isRunning()) {
            stop(context)
            false
        } else {
            start(context)
            true
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var config: LowRamConfig
    private lateinit var statusText: TextView
    private lateinit var ramUsageText: TextView
    private lateinit var toggleBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LowRamConfig(this)
        setContentView(buildMinimalistDarkUi())
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardState()
    }

    private fun buildMinimalistDarkUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D0F12")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(28), pad, dp(40))
        }
        root.addView(TextView(this).apply {
            text = "AIRTOUCH AI :: GESTURE ENGINE"
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
        })
        root.addView(TextView(this).apply {
            text = "전면 카메라 비접촉 손동작 컨트롤 · 초저메모리(Low-RAM) 런처 통합 모듈"
            textSize = 12.5f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(4), 0, dp(16))
        })
        statusText = TextView(this).apply {
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#181B20"))
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        root.addView(statusText)
        ramUsageText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(ramUsageText)

        root.addView(createActionButton("[ 1. 접근성 서비스(터치/스크롤 권한) 켜기 ]") {
            LauncherAirTouchBridge.openAccessibilitySettings(this)
        })
        root.addView(createActionButton("[ 2. 전면 카메라 권한 허용 ]") {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        })
        toggleBtn = createActionButton("[ 3. AirTouch 제스처 추적 시작 ]", isPrimary = true) {
            if (!hasCameraPermission()) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
                return@createActionButton
            }
            if (!LauncherAirTouchBridge.isAccessibilityEnabled(this)) {
                Toast.makeText(this, "먼저 접근성 설정에서 'AirTouch 제스처 컨트롤'을 켜주세요.", Toast.LENGTH_LONG).show()
                LauncherAirTouchBridge.openAccessibilitySettings(this)
                return@createActionButton
            }
            val nowRunning = LauncherAirTouchBridge.toggle(this)
            Toast.makeText(
                this,
                if (nowRunning) "AirTouch 추적 시작 (전면 카메라에 손을 보여주세요)" else "AirTouch 종료 (RAM 반환 완료)",
                Toast.LENGTH_SHORT
            ).show()
            refreshDashboardState()
        }
        root.addView(toggleBtn)

        root.addView(createSectionHeader("LOW-RAM & CURSOR TUNING (메모리·감도 설정)"))
        val speedLabel = TextView(this).apply {
            text = "커서 이동 속도 배율: ${String.format("%.2f", config.cursorSpeed)}x"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        root.addView(speedLabel)
        root.addView(SeekBar(this).apply {
            max = 200
            progress = ((config.cursorSpeed - 0.5f) * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = 0.5f + (progress / 100f)
                    config.cursorSpeed = v
                    speedLabel.text = "커서 이동 속도 배율: ${String.format("%.2f", v)}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        val fpsLabel = TextView(this).apply {
            text = "추적 활성 FPS: ${config.activeFps} FPS (미감지 대기 시 ${config.idleFps} FPS 자동 전환)"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(fpsLabel)
        root.addView(SeekBar(this).apply {
            max = 25
            progress = config.activeFps - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val fps = (progress + 5).coerceIn(5, 30)
                    config.activeFps = fps
                    fpsLabel.text = "추적 활성 FPS: $fps FPS (미감지 대기 시 ${config.idleFps} FPS 자동 전환)"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        root.addView(Switch(this).apply {
            text = "손바닥 상하좌우 스와이프 스크롤 사용"
            setTextColor(Color.WHITE)
            isChecked = config.swipeScrollEnabled
            setPadding(0, dp(12), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.swipeScrollEnabled = checked }
        })

        root.addView(createSectionHeader("HAND POSE MAPPING (손 모양 0.6초 유지 시 실행 동작)"))
        for (pose in HandPose.values()) {
            val rowBtn = createActionButton("") {}
            fun updateLabel() {
                val current = config.getActionForPose(pose)
                rowBtn.text = "${pose.labelKo}\n  ↳ 실행: ${current.titleKo} (터치하여 변경)"
            }
            updateLabel()
            rowBtn.setOnClickListener {
                val actions = AirTouchAction.values()
                val names = actions.map { it.titleKo }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("${pose.labelKo} 동작 선택")
                    .setItems(names) { _, which ->
                        config.setActionForPose(pose, actions[which])
                        updateLabel()
                    }
                    .show()
            }
            root.addView(rowBtn)
        }
        scroll.addView(root)
        return scroll
    }

    private fun refreshDashboardState() {
        val camOk = hasCameraPermission()
        val accOk = LauncherAirTouchBridge.isAccessibilityEnabled(this)
        val running = LauncherAirTouchBridge.isRunning()
        statusText.text = buildString {
            appendLine("• 전면 카메라 권한 : ${if (camOk) "허용됨 (OK)" else "미허용 (권한 필요)"}")
            appendLine("• 접근성 서비스    : ${if (accOk) "활성화됨 (OK)" else "비활성 (설정 필요)"}")
            appendLine("• AirTouch 엔진    : ${if (running) "실행 중 (ACTIVE)" else "대기 중 (0MB 유휴 상태)"}")
            append("• 분석 해상도      : ${config.cameraWidth}x${config.cameraHeight} (QVGA 초경량 버퍼)")
        }
        val pssMb = getAppPssMemoryMb()
        ramUsageText.text = "현재 앱 총 RAM 사용량(PSS): 약 ${pssMb} MB (끔 상태에서는 카메라·모델 0 MB)"
        toggleBtn.text = if (running) {
            "[ AirTouch 제스처 추적 중지 (RAM 즉시 반환) ]"
        } else {
            "[ 3. AirTouch 제스처 추적 시작 ]"
        }
    }

    private fun getAppPssMemoryMb(): Int {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            if (memInfo.isNotEmpty()) memInfo[0].totalPss / 1024 else 0
        } catch (_: Exception) {
            (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, dp(22), 0, dp(8))
        }
    }

    private fun createActionButton(label: String, isPrimary: Boolean = false, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 13.5f
            setTextColor(if (isPrimary) Color.BLACK else Color.WHITE)
            setBackgroundColor(if (isPrimary) Color.parseColor("#00E676") else Color.parseColor("#1E2229"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            val p = dp(12)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

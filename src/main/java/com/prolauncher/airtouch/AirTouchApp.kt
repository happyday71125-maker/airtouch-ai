package com.prolauncher.airtouch

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.Size
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
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
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

enum class AirTouchAction(val titleKo: String) {
    NONE("동작 없음"), TAP_AT_CURSOR("커서 위치 단일 클릭 (Tap)"), LONG_PRESS_AT_CURSOR("커서 위치 길게 누르기 (Long Press)"),
    DOUBLE_TAP_AT_CURSOR("커서 위치 더블 클릭"), SCROLL_DOWN("아래로 스크롤 (다음 내용)"), SCROLL_UP("위로 스크롤 (이전 내용)"),
    SWIPE_LEFT("왼쪽으로 스와이프"), SWIPE_RIGHT("오른쪽으로 스와이프"), GLOBAL_BACK("뒤로 가기 (Back)"),
    GLOBAL_HOME("홈 화면 이동 (Home)"), GLOBAL_RECENTS("최근 앱 목록 (Recents)"), GLOBAL_NOTIFICATIONS("알림창 열기"),
    GLOBAL_QUICK_SETTINGS("빠른 설정창 열기"), GLOBAL_LOCK_SCREEN("화면 잠금 (Lock Screen)"), GLOBAL_SCREENSHOT("화면 캡처 (Screenshot)"),
    MEDIA_PLAY_PAUSE("미디어 재생 / 일시정지"), MEDIA_NEXT("다음 곡 / 영상"), MEDIA_PREV("이전 곡 / 영상"),
    VOLUME_UP("볼륨 높이기 (+)"), VOLUME_DOWN("볼륨 낮추기 (-)"), TOGGLE_PAUSE_TRACKING("커서 일시정지 / 재개 토글")
}

enum class HandPose(val key: String, val labelKo: String, val howToKo: String, val defaultAction: AirTouchAction) {
    PEACE("pose_peace", "✌ 브이 (검지+중지)", "검지·중지 2개만 펴고 약지·새끼는 접은 채 0.6초 유지", AirTouchAction.GLOBAL_BACK),
    THREE_FINGERS("pose_three", "☝ 세 손가락 (검지+중지+약지)", "검지·중지·약지 3개를 펴고 새끼는 접은 채 0.6초 유지", AirTouchAction.GLOBAL_RECENTS),
    FOUR_FINGERS("pose_four", "✋ 네 손가락 (엄지만 접음)", "엄지만 손바닥 안으로 접고 나머지 4개를 편 채 0.6초 유지", AirTouchAction.VOLUME_UP),
    OPEN_PALM("pose_palm", "✋ 손바닥 활짝 (5개 모두 폄)", "엄지 포함 다섯 손가락을 모두 활짝 편 채 0.6초 유지", AirTouchAction.GLOBAL_HOME),
    ROCK_ON("pose_rock", "[락앤롤] (검지+새끼)", "검지와 새끼만 펴고 중지·약지는 접은 채 0.6초 유지", AirTouchAction.GLOBAL_LOCK_SCREEN),
    SHAKA("pose_pinky", "[샤카] (엄지+새끼)", "엄지와 새끼만 펴고 검지·중지·약지는 접은 채 0.6초 유지", AirTouchAction.VOLUME_DOWN),
    THUMB_UP("pose_thumb_up", "[엄지척] (엄지만 폄)", "엄지만 펴고 나머지 4개는 주먹처럼 접은 채 0.6초 유지", AirTouchAction.MEDIA_PLAY_PAUSE),
    FIST("pose_fist", "✊ 주먹 쥐기 (5개 모두 접음)", "다섯 손가락을 모두 말아 쥔 채 0.6초 유지", AirTouchAction.TOGGLE_PAUSE_TRACKING)
}

class LowRamConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("airtouch_lowram_prefs", Context.MODE_PRIVATE)
    var cameraWidth: Int get() = prefs.getInt("camera_width", 240); set(v) = prefs.edit().putInt("camera_width", v).apply()
    var cameraHeight: Int get() = prefs.getInt("camera_height", 320); set(v) = prefs.edit().putInt("camera_height", v).apply()
    var activeFps: Int get() = prefs.getInt("active_fps", 15); set(v) = prefs.edit().putInt("active_fps", v.coerceIn(5, 30)).apply()
    var idleFps: Int get() = prefs.getInt("idle_fps", 4); set(v) = prefs.edit().putInt("idle_fps", v.coerceIn(1, 10)).apply()
    var useGpuDelegate: Boolean get() = prefs.getBoolean("use_gpu_delegate", false); set(v) = prefs.edit().putBoolean("use_gpu_delegate", v).apply()
    var cursorSpeed: Float get() = prefs.getFloat("cursor_speed", 1.45f); set(v) = prefs.edit().putFloat("cursor_speed", v.coerceIn(0.5f, 2.5f)).apply()
    var smoothingAlpha: Float get() = prefs.getFloat("smoothing_alpha", 0.35f); set(v) = prefs.edit().putFloat("smoothing_alpha", v.coerceIn(0.1f, 0.9f)).apply()
    var pinchRatioThreshold: Float get() = prefs.getFloat("pinch_ratio_threshold", 0.33f); set(v) = prefs.edit().putFloat("pinch_ratio_threshold", v.coerceIn(0.18f, 0.50f)).apply()
    var dwellClickEnabled: Boolean get() = prefs.getBoolean("dwell_click_enabled", false); set(v) = prefs.edit().putBoolean("dwell_click_enabled", v).apply()
    var dwellDurationMs: Long get() = prefs.getLong("dwell_duration_ms", 1000L); set(v) = prefs.edit().putLong("dwell_duration_ms", v.coerceIn(500L, 2000L)).apply()
    var poseHoldDurationMs: Long get() = prefs.getLong("pose_hold_ms", 600L); set(v) = prefs.edit().putLong("pose_hold_ms", v.coerceIn(300L, 1500L)).apply()
    var swipeScrollEnabled: Boolean get() = prefs.getBoolean("swipe_scroll_enabled", true); set(v) = prefs.edit().putBoolean("swipe_scroll_enabled", v).apply()
    fun getActionForPose(pose: HandPose): AirTouchAction = runCatching { AirTouchAction.valueOf(prefs.getString(pose.key, pose.defaultAction.name)!!) }.getOrDefault(pose.defaultAction)
    fun setActionForPose(pose: HandPose, action: AirTouchAction) = prefs.edit().putString(pose.key, action.name).apply()
}

object AirTouchTelemetry {
    @Volatile var handDetected: Boolean = false
    @Volatile var fingerStatesText: String = "손 대기 중..."
    @Volatile var currentModeText: String = "카메라 앞에 손을 보여주세요"
    @Volatile var pinchRatio: Float = 1.0f
    @Volatile var lastTriggeredActionText: String = "없음"
}

class GestureClassifier(private val config: LowRamConfig) {
    data class FrameEvaluation(
        val normCursorX: Float, val normCursorY: Float, val isPinching: Boolean, val pinchProgress: Float,
        val pinchJustClicked: Boolean, val pinchLongPressed: Boolean, val dwellJustClicked: Boolean, val dwellProgress: Float,
        val detectedPose: HandPose?, val poseHoldProgress: Float, val triggeredPoseAction: AirTouchAction?,
        val triggeredSwipeAction: AirTouchAction?, val trackingPaused: Boolean, val modeSummary: String
    )
    private var smoothedX = 0.5f; private var smoothedY = 0.5f; private var lockedX = 0.5f; private var lockedY = 0.5f
    private var isCursorLockedForPinch = false; private var isCurrentlyPinching = false; private var pinchStartTimeMs = 0L
    private var longPressTriggeredInCurrentPinch = false; private var dwellAnchorX = 0.5f; private var dwellAnchorY = 0.5f
    private var dwellStartTimeMs = 0L; private var dwellTriggeredAtCurrentSpot = false
    private var currentCandidatePose: HandPose? = null; private var poseStartTimeMs = 0L; private var poseTriggeredInCurrentHold = false
    private var lastPalmX = -1f; private var lastPalmY = -1f; private var lastPalmTimeMs = 0L; private var lastSwipeTriggerTimeMs = 0L
    var isTrackingPaused: Boolean = false; private set

    fun evaluate(lm: List<NormalizedLandmark>): FrameEvaluation {
        val now = SystemClock.uptimeMillis()
        val wrist = lm[0]; val thumbTip = lm[4]; val indexMcp = lm[5]; val indexPip = lm[6]; val indexTip = lm[8]; val middleMcp = lm[9]
        val handScale = dist(wrist, middleMcp).coerceAtLeast(0.07f)
        val effectivePinchRatio = minOf(dist(thumbTip, indexTip) / handScale, (dist(thumbTip, indexPip) / handScale) * 1.05f)
        AirTouchTelemetry.pinchRatio = effectivePinchRatio
        val ratioThreshold = config.pinchRatioThreshold
        val isApproachingPinch = effectivePinchRatio < (ratioThreshold * 1.45f)

        val ptrRawX = 1.0f - (indexTip.x() * 0.7f + indexMcp.x() * 0.3f)
        val ptrRawY = (indexTip.y() * 0.7f + indexMcp.y() * 0.3f)
        val mappedX = ((ptrRawX - 0.5f) * config.cursorSpeed + 0.5f).coerceIn(0f, 1f)
        val mappedY = ((ptrRawY - 0.5f) * config.cursorSpeed + 0.5f).coerceIn(0f, 1f)

        if (!isApproachingPinch) {
            isCursorLockedForPinch = false
            if (hypot(mappedX - smoothedX, mappedY - smoothedY) > 0.003f) {
                smoothedX += (mappedX - smoothedX) * config.smoothingAlpha
                smoothedY += (mappedY - smoothedY) * config.smoothingAlpha
            }
            lockedX = smoothedX; lockedY = smoothedY
        } else if (!isCursorLockedForPinch) {
            isCursorLockedForPinch = true; lockedX = smoothedX; lockedY = smoothedY
        }
        val finalX = if (isCursorLockedForPinch) lockedX else smoothedX
        val finalY = if (isCursorLockedForPinch) lockedY else smoothedY

        val thumbUp = isThumbExtended(lm, handScale)
        val indexUp = isFingerExtended(wrist, lm[5], lm[6], lm[8])
        val middleUp = isFingerExtended(wrist, lm[9], lm[10], lm[12])
        val ringUp = isFingerExtended(wrist, lm[13], lm[14], lm[16])
        val pinkyUp = isFingerExtended(wrist, lm[17], lm[18], lm[20])
        AirTouchTelemetry.fingerStatesText = "엄지:${if (thumbUp) "O" else "X"} 검지:${if (indexUp) "O" else "X"} 중지:${if (middleUp) "O" else "X"} 약지:${if (ringUp) "O" else "X"} 새끼:${if (pinkyUp) "O" else "X"}"

        val pinchActive = if (isCurrentlyPinching) effectivePinchRatio < (ratioThreshold * 1.28f) else effectivePinchRatio < ratioThreshold
        var pinchJustClicked = false; var pinchLongPressed = false; var pinchProgress = 0f
        if (pinchActive && !middleUp && !ringUp) {
            if (!isCurrentlyPinching) {
                isCurrentlyPinching = true; pinchStartTimeMs = now; longPressTriggeredInCurrentPinch = false
            } else {
                val heldMs = now - pinchStartTimeMs
                pinchProgress = (heldMs.toFloat() / 650f).coerceIn(0f, 1f)
                if (!longPressTriggeredInCurrentPinch && heldMs >= 650L) {
                    if (!isTrackingPaused) { pinchLongPressed = true; AirTouchTelemetry.lastTriggeredActionText = "길게 누르기 (Long Press)" }
                    longPressTriggeredInCurrentPinch = true
                }
            }
        } else if (isCurrentlyPinching) {
            val heldMs = now - pinchStartTimeMs
            if (!longPressTriggeredInCurrentPinch && heldMs in 25L..649L && !isTrackingPaused) {
                pinchJustClicked = true; AirTouchTelemetry.lastTriggeredActionText = "클릭 성공! (Pinch Tap)"
            }
            isCurrentlyPinching = false
        }

        val isPointingOnly = indexUp && !middleUp && !ringUp && !pinkyUp && !isCurrentlyPinching
        var dwellProgress = 0f; var dwellJustClicked = false
        if (config.dwellClickEnabled && isPointingOnly && !isTrackingPaused) {
            if (hypot(finalX - dwellAnchorX, finalY - dwellAnchorY) > 0.022f) {
                dwellAnchorX = finalX; dwellAnchorY = finalY; dwellStartTimeMs = now; dwellTriggeredAtCurrentSpot = false
            } else {
                val elapsed = now - dwellStartTimeMs
                dwellProgress = (elapsed.toFloat() / config.dwellDurationMs.toFloat()).coerceIn(0f, 1f)
                if (!dwellTriggeredAtCurrentSpot && elapsed >= config.dwellDurationMs) {
                    dwellTriggeredAtCurrentSpot = true; dwellJustClicked = true
                    AirTouchTelemetry.lastTriggeredActionText = "머물러 자동 클릭! (Dwell Tap)"
                }
            }
        } else { dwellStartTimeMs = now; dwellTriggeredAtCurrentSpot = false }

        val rawPose = if (isCurrentlyPinching || isPointingOnly) null else when {
            indexUp && middleUp && !ringUp && !pinkyUp -> HandPose.PEACE
            indexUp && middleUp && ringUp && !pinkyUp -> HandPose.THREE_FINGERS
            thumbUp && indexUp && middleUp && ringUp && pinkyUp -> HandPose.OPEN_PALM
            !thumbUp && indexUp && middleUp && ringUp && pinkyUp -> HandPose.FOUR_FINGERS
            indexUp && !middleUp && !ringUp && pinkyUp && !thumbUp -> HandPose.ROCK_ON
            thumbUp && !indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.SHAKA
            thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.THUMB_UP
            !thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.FIST
            else -> null
        }

        var poseProgress = 0f; var triggeredPoseAction: AirTouchAction? = null
        if (rawPose != null) {
            if (rawPose != currentCandidatePose) {
                currentCandidatePose = rawPose; poseStartTimeMs = now; poseTriggeredInCurrentHold = false
            } else {
                val elapsed = now - poseStartTimeMs
                poseProgress = (elapsed.toFloat() / config.poseHoldDurationMs.toFloat()).coerceIn(0f, 1f)
                if (!poseTriggeredInCurrentHold && elapsed >= config.poseHoldDurationMs) {
                    poseTriggeredInCurrentHold = true
                    val mapped = config.getActionForPose(rawPose)
                    if (mapped == AirTouchAction.TOGGLE_PAUSE_TRACKING) {
                        isTrackingPaused = !isTrackingPaused; triggeredPoseAction = mapped
                        AirTouchTelemetry.lastTriggeredActionText = if (isTrackingPaused) "일시정지 켜짐" else "일시정지 해제됨"
                    } else if (!isTrackingPaused) {
                        triggeredPoseAction = mapped; AirTouchTelemetry.lastTriggeredActionText = "${rawPose.labelKo} -> ${mapped.titleKo}"
                    }
                }
            }
        } else { currentCandidatePose = null; poseTriggeredInCurrentHold = false }

        var triggeredSwipeAction: AirTouchAction? = null
        val palmX = 1.0f - ((wrist.x() + middleMcp.x()) * 0.5f)
        val palmY = (wrist.y() + middleMcp.y()) * 0.5f
        if (config.swipeScrollEnabled && !isTrackingPaused && !isCurrentlyPinching) {
            val dt = now - lastPalmTimeMs
            if (lastPalmX >= 0f && dt in 35L..210L && (now - lastSwipeTriggerTimeMs) > 650L) {
                val vx = (palmX - lastPalmX) / (dt / 1000f); val vy = (palmY - lastPalmY) / (dt / 1000f)
                if (abs(vy) > abs(vx) && abs(vy) > 1.35f) {
                    triggeredSwipeAction = if (vy < 0) AirTouchAction.SCROLL_DOWN else AirTouchAction.SCROLL_UP
                    lastSwipeTriggerTimeMs = now; AirTouchTelemetry.lastTriggeredActionText = triggeredSwipeAction.titleKo
                } else if (abs(vx) > abs(vy) && abs(vx) > 1.35f) {
                    triggeredSwipeAction = if (vx < 0) AirTouchAction.SWIPE_LEFT else AirTouchAction.SWIPE_RIGHT
                    lastSwipeTriggerTimeMs = now; AirTouchTelemetry.lastTriggeredActionText = triggeredSwipeAction.titleKo
                }
            }
        }
        lastPalmX = palmX; lastPalmY = palmY; lastPalmTimeMs = now

        val summary = when {
            isTrackingPaused -> "[일시정지] 주먹 0.6초 쥐어 해제"
            isCurrentlyPinching -> "◎ 클릭 감지 중 (떼면 클릭 / 유지시 롱클릭)"
            rawPose != null -> "${rawPose.labelKo} (${(poseProgress * 100).toInt()}%)"
            isPointingOnly -> "◎ 포인터 이동 중 (엄지+검지 맞대면 클릭)"
            else -> "● 손 인식됨 (검지만 펴면 커서 조준)"
        }
        AirTouchTelemetry.currentModeText = summary
        return FrameEvaluation(finalX, finalY, isCurrentlyPinching, pinchProgress, pinchJustClicked, pinchLongPressed, dwellJustClicked, dwellProgress, currentCandidatePose, poseHoldProgress = poseProgress, triggeredPoseAction, triggeredSwipeAction, isTrackingPaused, summary)
    }

    private fun isFingerExtended(wrist: NormalizedLandmark, mcp: NormalizedLandmark, pip: NormalizedLandmark, tip: NormalizedLandmark): Boolean {
        if (dist(wrist, tip) <= dist(wrist, pip) * 1.08f || dist(wrist, tip) <= dist(wrist, mcp) * 1.25f) return false
        val v1x = pip.x() - mcp.x(); val v1y = pip.y() - mcp.y(); val v2x = tip.x() - pip.x(); val v2y = tip.y() - pip.y()
        val cos = (v1x * v2x + v1y * v2y) / (sqrt(v1x * v1x + v1y * v1y).coerceAtLeast(1e-4f) * sqrt(v2x * v2x + v2y * v2y).coerceAtLeast(1e-4f))
        return cos > 0.45f
    }

    private fun isThumbExtended(lm: List<NormalizedLandmark>, handScale: Float): Boolean {
        val wrist = lm[0]; val cmc = lm[1]; val mcp = lm[2]; val ip = lm[3]; val tip = lm[4]
        val v1x = ip.x() - cmc.x(); val v1y = ip.y() - cmc.y(); val v2x = tip.x() - mcp.x(); val v2y = tip.y() - mcp.y()
        val cos = (v1x * v2x + v1y * v2y) / (sqrt(v1x * v1x + v1y * v1y).coerceAtLeast(1e-4f) * sqrt(v2x * v2x + v2y * v2y).coerceAtLeast(1e-4f))
        return (dist(tip, lm[5]) / handScale > 0.52f) && (dist(tip, lm[9]) / handScale > 0.62f) && (dist(wrist, tip) > dist(wrist, mcp) * 1.12f) && cos > 0.50f
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float = hypot(a.x() - b.x(), a.y() - b.y())
}

class CursorOverlayView(context: Context) : View(context) {
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.parseColor("#00E676") }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00B0FF") }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#CC101418") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
    private val arcRect = RectF(); private val pillRect = RectF()
    var cursorX = 540f; var cursorY = 960f; var isPinching = false; var isPaused = false; var gaugeProgress = 0f; var statusLabel = "AirTouch 대기 중"

    fun updateState(x: Float, y: Float, pinching: Boolean, paused: Boolean, progress: Float, label: String) {
        cursorX = x; cursorY = y; isPinching = pinching; isPaused = paused; gaugeProgress = progress; statusLabel = label
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = if (isPinching) 22f else 34f
        outerRingPaint.color = when { isPaused -> Color.parseColor("#FF5252"); isPinching -> Color.parseColor("#FFD740"); else -> Color.parseColor("#00E676") }
        canvas.drawCircle(cursorX, cursorY, r, outerRingPaint)
        canvas.drawCircle(cursorX, cursorY, if (isPinching) 12f else 7f, centerDotPaint)
        if (gaugeProgress > 0.04f) {
            arcRect.set(cursorX - r - 12f, cursorY - r - 12f, cursorX + r + 12f, cursorY + r + 12f)
            canvas.drawArc(arcRect, -90f, 360f * gaugeProgress, false, progressArcPaint)
        }
        val tw = labelPaint.measureText(statusLabel); val pw = tw + 48f; val pl = ((width - pw) * 0.5f).coerceAtLeast(16f)
        pillRect.set(pl, 96f, pl + pw, 158f)
        canvas.drawRoundRect(pillRect, 31f, 31f, pillBgPaint)
        canvas.drawText(statusLabel, pl + 24f, 137f, labelPaint)
    }
}

class AirTouchAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: AirTouchAccessibilityService? = null; private set }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null; private var cursorView: CursorOverlayView? = null
    private var screenWidth = 1080; private var screenHeight = 2400

    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
        if (AirTouchTrackingService.isRunning) showCursorOverlay()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { hideCursorOverlay(); if (instance === this) instance = null; super.onDestroy() }

    private fun updateScreenMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds; screenWidth = b.width(); screenHeight = b.height()
        } else {
            val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels; screenHeight = dm.heightPixels
        }
    }

    fun showCursorOverlay() = mainHandler.post {
        if (cursorView != null) return@post
        val wm = windowManager ?: return@post
        updateScreenMetrics()
        val view = CursorOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(view, params); cursorView = view }
    }

    fun hideCursorOverlay() = mainHandler.post {
        val view = cursorView ?: return@post
        runCatching { windowManager?.removeView(view) }
        cursorView = null
    }

    fun notifyNoHandDetected() = mainHandler.post {
        cursorView?.let { it.updateState(it.cursorX, it.cursorY, false, false, 0f, "전면 카메라에 손을 보여주세요") }
    }

    fun updateCursorFromEvaluation(eval: GestureClassifier.FrameEvaluation) = mainHandler.post {
        if (cursorView == null) showCursorOverlay()
        val px = (eval.normCursorX * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val py = (eval.normCursorY * screenHeight).coerceIn(0f, screenHeight.toFloat())
        cursorView?.updateState(px, py, eval.isPinching, eval.trackingPaused, maxOf(eval.poseHoldProgress, eval.pinchProgress, eval.dwellProgress), eval.modeSummary)
        if (eval.pinchJustClicked || eval.dwellJustClicked) performAction(AirTouchAction.TAP_AT_CURSOR, px, py)
        else if (eval.pinchLongPressed) performAction(AirTouchAction.LONG_PRESS_AT_CURSOR, px, py)
        eval.triggeredPoseAction?.let { if (it != AirTouchAction.TOGGLE_PAUSE_TRACKING) performAction(it, px, py) }
        eval.triggeredSwipeAction?.let { performAction(it, px, py) }
    }

    fun performAction(action: AirTouchAction, x: Float = screenWidth / 2f, y: Float = screenHeight / 2f) {
        when (action) {
            AirTouchAction.NONE, AirTouchAction.TOGGLE_PAUSE_TRACKING -> Unit
            AirTouchAction.TAP_AT_CURSOR -> dispatchTap(x, y, 50L)
            AirTouchAction.LONG_PRESS_AT_CURSOR -> dispatchTap(x, y, 580L)
            AirTouchAction.DOUBLE_TAP_AT_CURSOR -> { dispatchTap(x, y, 45L); mainHandler.postDelayed({ dispatchTap(x, y, 45L) }, 120L) }
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
            AirTouchAction.VOLUME_UP -> (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            AirTouchAction.VOLUME_DOWN -> (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val p = Path().apply { moveTo(x.coerceIn(1f, (screenWidth - 1).toFloat()), y.coerceIn(1f, (screenHeight - 1).toFloat())) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), null, null)
    }

    private fun dispatchSwipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), null, null)
    }

    private fun sendMediaKey(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager; val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}

class AirTouchTrackingService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.prolauncher.airtouch.ACTION_START"
        const val ACTION_STOP = "com.prolauncher.airtouch.ACTION_STOP"
        private const val CHANNEL_ID = "airtouch_tracking_channel"
        private const val NOTIFICATION_ID = 4102
        @Volatile var isRunning: Boolean = false; private set
    }
    private lateinit var config: LowRamConfig; private lateinit var classifier: GestureClassifier
    private var cameraExecutor: ExecutorService? = null; private var cameraProvider: ProcessCameraProvider? = null
    private var handLandmarker: HandLandmarker? = null; private var reusableBitmap: Bitmap? = null
    private val rotationMatrix = Matrix()
    @Volatile private var lastFrameProcessTimeMs = 0L; @Volatile private var lastHandDetectedTimeMs = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> runCatching { cameraProvider?.unbindAll() }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> cameraProvider?.let { bindAnalysisUseCase(it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = LowRamConfig(this); classifier = GestureClassifier(config); cameraExecutor = Executors.newSingleThreadExecutor()
        registerReceiver(screenStateReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTIFICATION_ID, notif)
        isRunning = true; lastHandDetectedTimeMs = SystemClock.uptimeMillis()
        initHandLandmarker(); startCameraPipeline(); AirTouchAccessibilityService.instance?.showCursorOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false; AirTouchTelemetry.handDetected = false; AirTouchTelemetry.currentModeText = "추적 중지됨 (0MB 유휴 상태)"
        runCatching { unregisterReceiver(screenStateReceiver) }
        AirTouchAccessibilityService.instance?.hideCursorOverlay()
        runCatching { cameraProvider?.unbindAll(); cameraProvider = null }
        runCatching { handLandmarker?.close(); handLandmarker = null }
        reusableBitmap?.recycle(); reusableBitmap = null
        cameraExecutor?.shutdownNow(); cameraExecutor = null
        super.onDestroy()
    }

    private fun initHandLandmarker() {
        if (handLandmarker != null) return
        runCatching {
            val baseBuilder = BaseOptions.builder().setDelegate(if (config.useGpuDelegate) Delegate.GPU else Delegate.CPU)
            val customModel = File(filesDir, "hand_landmarker.task")
            if (customModel.exists() && customModel.length() > 100_000L) {
                FileInputStream(customModel).use { baseBuilder.setModelAssetBuffer(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, customModel.length())) }
            } else baseBuilder.setModelAssetPath("hand_landmarker.task")
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseBuilder.build()).setNumHands(1)
                .setMinHandDetectionConfidence(0.45f).setMinHandPresenceConfidence(0.45f).setMinTrackingConfidence(0.45f)
                .setRunningMode(RunningMode.LIVE_STREAM).setResultListener(this::onHandLandmarkResult).build()
            handLandmarker = HandLandmarker.createFromOptions(this, opts)
        }
    }

    private fun startCameraPipeline() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cameraProvider = future.get(); cameraProvider?.let { bindAnalysisUseCase(it) } }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalysisUseCase(provider: ProcessCameraProvider) {
        val exec = cameraExecutor ?: return
        runCatching {
            provider.unbindAll()
            val resSelector = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(config.cameraWidth, config.cameraHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val analysis = ImageAnalysis.Builder().setResolutionSelector(resSelector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            analysis.setAnalyzer(exec, this::processCameraFrame)
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis(); val landmarker = handLandmarker
        if (landmarker == null) { imageProxy.close(); return }
        val targetFps = if ((now - lastHandDetectedTimeMs) > 3000L) config.idleFps else config.activeFps
        if ((now - lastFrameProcessTimeMs) < (1000L / targetFps.coerceAtLeast(1))) { imageProxy.close(); return }
        lastFrameProcessTimeMs = now
        try {
            val w = imageProxy.width; val h = imageProxy.height; val rot = imageProxy.imageInfo.rotationDegrees
            var bmp = reusableBitmap
            if (bmp == null || bmp.width != w || bmp.height != h) { bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); reusableBitmap = bmp }
            imageProxy.planes[0].buffer.rewind(); bmp.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            val finalBmp = if (rot != 0) { rotationMatrix.reset(); rotationMatrix.postRotate(rot.toFloat()); Bitmap.createBitmap(bmp, 0, 0, w, h, rotationMatrix, false) } else bmp
            landmarker.detectAsync(BitmapImageBuilder(finalBmp).build(), now)
        } catch (_: Exception) {} finally { imageProxy.close() }
    }

    private fun onHandLandmarkResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        val hands = result.landmarks()
        if (hands.isNullOrEmpty()) {
            AirTouchTelemetry.handDetected = false; AirTouchTelemetry.fingerStatesText = "손 미감지 (카메라 앞으로 손을 들어 올려주세요)"
            AirTouchTelemetry.currentModeText = "손 대기 중..."; AirTouchAccessibilityService.instance?.notifyNoHandDetected()
            return
        }
        AirTouchTelemetry.handDetected = true; lastHandDetectedTimeMs = SystemClock.uptimeMillis()
        AirTouchAccessibilityService.instance?.updateCursorFromEvaluation(classifier.evaluate(hands[0]))
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AirTouch 백그라운드 추적", NotificationManager.IMPORTANCE_LOW))
        val stopPending = PendingIntent.getService(this, 1, Intent(this, AirTouchTrackingService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPending = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AirTouch AI v2.0 실행 중").setContentText("검지로 조준 · 엄지+검지 맞대어 클릭 · 터치하여 설정 열기")
            .setContentIntent(openPending).addAction(android.R.drawable.ic_menu_close_clear_cancel, "즉시 끄기 (RAM 반환)", stopPending).setOngoing(true).build()
    }
}

class AirTouchTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); syncTileState() }
    override fun onClick() { super.onClick(); LauncherAirTouchBridge.toggle(this); syncTileState() }
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
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(expected) == true
    }
    fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, AirTouchTrackingService::class.java).apply { action = AirTouchTrackingService.ACTION_START })
    fun stop(context: Context) { context.startService(Intent(context, AirTouchTrackingService::class.java).apply { action = AirTouchTrackingService.ACTION_STOP }) }
    fun toggle(context: Context): Boolean = if (isRunning()) { stop(context); false } else { start(context); true }
    fun openAccessibilitySettings(context: Context) = context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
}

class MainActivity : AppCompatActivity() {
    private lateinit var config: LowRamConfig; private lateinit var statusText: TextView
    private lateinit var liveTelemetryText: TextView; private lateinit var ramUsageText: TextView
    private lateinit var toggleBtn: Button; private lateinit var practiceBtn: Button
    private var practiceClickCount = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable { override fun run() { updateLiveTelemetryCard(); uiHandler.postDelayed(this, 180L) } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); config = LowRamConfig(this); setContentView(buildUi()) }
    override fun onResume() { super.onResume(); refreshDashboardState(); uiHandler.post(uiRefreshRunnable) }
    override fun onPause() { uiHandler.removeCallbacks(uiRefreshRunnable); super.onPause() }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D0F12")) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(40)) }
        root.addView(TextView(this).apply { text = "AIRTOUCH AI v2.0 :: GESTURE ENGINE"; textSize = 19f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676")) })
        root.addView(TextView(this).apply { text = "전면 카메라 비접촉 손동작 컨트롤 · 흔들림 방지 클릭 & 8종 손 모양"; textSize = 12f; setTextColor(Color.parseColor("#9E9E9E")); setPadding(0, dp(4), 0, dp(14)) })

        statusText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#181B20")); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        root.addView(statusText)

        root.addView(createHeader("LIVE GESTURE MONITOR (실시간 손 인식 모니터)"))
        liveTelemetryText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#FFD740")); setBackgroundColor(Color.parseColor("#141920")); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        root.addView(liveTelemetryText)

        practiceBtn = createBtn("★ [클릭 연습 타겟] 커서를 여기에 올리고 엄지+검지를 맞대어 보세요! (성공: 0회)") {
            practiceClickCount++
            practiceBtn.text = "★ [클릭 성공!] 완벽합니다! 누적 클릭 성공: ${practiceClickCount}회"
            practiceBtn.setBackgroundColor(Color.parseColor("#1B5E20"))
        }
        root.addView(practiceBtn)

        ramUsageText = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#80D8FF")); setPadding(0, dp(4), 0, dp(12)) }
        root.addView(ramUsageText)

        root.addView(createBtn("[ 1. 접근성 서비스(터치/스크롤 권한) 켜기 ]") { LauncherAirTouchBridge.openAccessibilitySettings(this) })
        root.addView(createBtn("[ 2. 전면 카메라 권한 허용 ]") { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001) })
        toggleBtn = createBtn("[ 3. AirTouch 제스처 추적 시작 ]", true) {
            if (!hasCameraPermission()) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001); return@createBtn }
            if (!LauncherAirTouchBridge.isAccessibilityEnabled(this)) {
                Toast.makeText(this, "먼저 접근성 설정에서 'AirTouch 제스처 컨트롤'을 켜주세요.", Toast.LENGTH_LONG).show()
                LauncherAirTouchBridge.openAccessibilitySettings(this); return@createBtn
            }
            LauncherAirTouchBridge.toggle(this); refreshDashboardState()
        }
        root.addView(toggleBtn)

        root.addView(createHeader("HOW TO CLICK & CONTROL (구체적인 클릭 및 조작 방법)"))
        root.addView(TextView(this).apply {
            textSize = 12.8f; setTextColor(Color.parseColor("#E0E0E0")); setBackgroundColor(Color.parseColor("#16191E")); setPadding(dp(14), dp(14), dp(14), dp(14))
            text = "[1] 커서 조준 (☝ 검지 포인터 모드)\n• 카메라 앞 25~50cm 거리에 손을 들고 '검지 1개'만 펴고 중지·약지·새끼는 접으세요.\n\n" +
                "[2] 클릭하는 3가지 방법\n• (방법 A: 기본 핀치 클릭) 검지로 원하는 버튼을 가리킨 상태에서 '엄지 끝과 검지 끝'을 살짝 맞대었다가 떼면 즉시 클릭됩니다! (엄지·검지가 가까워질 때 커서가 흔들리지 않게 자동 고정됩니다.)\n" +
                "• (방법 B: 길게 누르기) 엄지와 검지를 맞댄 채 0.65초 유지하면 원형 게이지가 차면서 롱클릭됩니다.\n" +
                "• (방법 C: 머물러 자동 클릭) 아래 스위치에서 '머물러 자동 클릭'을 켜면, 커서를 버튼 위에 1초간 가만히 멈추기만 해도 자동 클릭됩니다.\n\n" +
                "[3] 상하좌우 스크롤\n• 손을 위/아래/좌/우로 빠르게 쓸어 넘기면 화면이 스크롤됩니다."
        })

        root.addView(createHeader("CLICK & CURSOR TUNING (클릭 방식 · 감도 조절)"))
        root.addView(Switch(this).apply {
            text = "머물러 자동 클릭 (Dwell Click: 커서 1초 고정 시 자동 클릭)"; setTextColor(Color.WHITE); isChecked = config.dwellClickEnabled; setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.dwellClickEnabled = checked }
        })

        val pinchLabel = TextView(this).apply { text = "핀치 클릭 민감도 (거리 비율): ${String.format("%.2f", config.pinchRatioThreshold)} (높을수록 살짝만 붙여도 클릭됨)"; setTextColor(Color.LTGRAY); textSize = 12.5f }
        root.addView(pinchLabel)
        root.addView(SeekBar(this).apply {
            max = 30; progress = ((config.pinchRatioThreshold - 0.18f) * 100f).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { val v = 0.18f + (p / 100f); config.pinchRatioThreshold = v; pinchLabel.text = "핀치 클릭 민감도 (거리 비율): ${String.format("%.2f", v)} (높을수록 살짝만 붙여도 클릭됨)" }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val speedLabel = TextView(this).apply { text = "커서 이동 속도 배율: ${String.format("%.2f", config.cursorSpeed)}x"; setTextColor(Color.LTGRAY); textSize = 12.5f; setPadding(0, dp(10), 0, 0) }
        root.addView(speedLabel)
        root.addView(SeekBar(this).apply {
            max = 200; progress = ((config.cursorSpeed - 0.5f) * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { val v = 0.5f + (p / 100f); config.cursorSpeed = v; speedLabel.text = "커서 이동 속도 배율: ${String.format("%.2f", v)}x" }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(Switch(this).apply {
            text = "손바닥 상하좌우 스와이프 스크롤 사용"; setTextColor(Color.WHITE); isChecked = config.swipeScrollEnabled; setPadding(0, dp(10), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.swipeScrollEnabled = checked }
        })

        root.addView(createHeader("8 HAND POSES (손 모양 0.6초 유지 시 실행 동작 · 터치하여 변경)"))
        for (pose in HandPose.values()) {
            val rowBtn = createBtn("") {}
            fun updateLabel() { rowBtn.text = "${pose.labelKo}  ->  [${config.getActionForPose(pose).titleKo}]\n  • 방법: ${pose.howToKo}" }
            updateLabel()
            rowBtn.setOnClickListener {
                val actions = AirTouchAction.values()
                AlertDialog.Builder(this).setTitle("${pose.labelKo} 실행 동작 변경").setItems(actions.map { it.titleKo }.toTypedArray()) { _, w -> config.setActionForPose(pose, actions[w]); updateLabel() }.show()
            }
            root.addView(rowBtn)
        }
        scroll.addView(root); return scroll
    }

    private fun updateLiveTelemetryCard() {
        if (!LauncherAirTouchBridge.isRunning()) { liveTelemetryText.text = "• 상태: 추적 대기 중 (아래 '3. AirTouch 제스처 추적 시작' 버튼을 눌러주세요)"; return }
        liveTelemetryText.text = "• 손 감지 여부 : ${if (AirTouchTelemetry.handDetected) "● 인식됨" else "○ 카메라 시야에 손 없음"}\n" +
            "• 손가락 펴짐  : ${AirTouchTelemetry.fingerStatesText}\n• 현재 모드    : ${AirTouchTelemetry.currentModeText}\n" +
            "• 핀치 거리비율: ${String.format("%.2f", AirTouchTelemetry.pinchRatio)} (임계값 ${String.format("%.2f", config.pinchRatioThreshold)} 이하로 맞닿으면 클릭!)\n" +
            "• 최근 실행동작: ${AirTouchTelemetry.lastTriggeredActionText}"
    }

    private fun refreshDashboardState() {
        val running = LauncherAirTouchBridge.isRunning()
        statusText.text = "• 전면 카메라 권한 : ${if (hasCameraPermission()) "허용됨 (OK)" else "미허용 (권한 필요)"}\n" +
            "• 접근성 서비스    : ${if (LauncherAirTouchBridge.isAccessibilityEnabled(this)) "활성화됨 (OK)" else "비활성 (설정 필요)"}\n" +
            "• AirTouch 엔진    : ${if (running) "실행 중 (ACTIVE)" else "대기 중 (0MB 유휴 상태)"}"
        val pssMb = runCatching { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss / 1024 }.getOrDefault(0)
        ramUsageText.text = "현재 앱 총 RAM 사용량(PSS): 약 ${pssMb} MB (끔 상태에서는 카메라·모델 0 MB)"
        toggleBtn.text = if (running) "[ AirTouch 제스처 추적 중지 (RAM 즉시 반환) ]" else "[ 3. AirTouch 제스처 추적 시작 ]"
        updateLiveTelemetryCard()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun createHeader(title: String): TextView = TextView(this).apply { text = title; textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676")); setPadding(0, dp(20), 0, dp(8)) }
    private fun createBtn(label: String, isPrimary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 13f
        setTextColor(if (isPrimary) Color.BLACK else Color.WHITE); setBackgroundColor(if (isPrimary) Color.parseColor("#00E676") else Color.parseColor("#1E2229"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { onClick() }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

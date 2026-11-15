package com.prolauncher.airtouch

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.media.AudioManager
import android.net.Uri
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
    PEACE("pose_peace", "\u270C\uFE0F 브이 (검지+중지)", "손을 제자리에 멈추고 검지·중지 2개만 편 채 0.6초 유지", AirTouchAction.GLOBAL_BACK),
    THREE_FINGERS("pose_three", "\u261D\uFE0F 세 손가락 (검지+중지+약지)", "손을 제자리에 멈추고 검지·중지·약지 3개를 편 채 0.6초 유지", AirTouchAction.GLOBAL_RECENTS),
    FOUR_FINGERS("pose_four", "\uD83D\uDD96 네 손가락 (엄지만 접음)", "손을 제자리에 멈추고 엄지만 접은 채 4손가락 0.6초 유지", AirTouchAction.VOLUME_UP),
    OPEN_PALM("pose_palm", "\uD83D\uDD90\uFE0F 손바닥 활짝 (5개 모두 폄)", "손을 제자리에 멈추고 다섯 손가락을 활짝 편 채 0.6초 유지", AirTouchAction.GLOBAL_HOME),
    ROCK_ON("pose_rock", "\uD83E\uDD18 락앤롤 (검지+새끼)", "손을 제자리에 멈추고 검지·새끼만 편 채 0.6초 유지", AirTouchAction.GLOBAL_LOCK_SCREEN),
    SHAKA("pose_pinky", "\uD83E\uDD19 샤카/전화 (엄지+새끼)", "손을 제자리에 멈추고 엄지·새끼만 편 채 0.6초 유지", AirTouchAction.VOLUME_DOWN),
    THUMB_UP("pose_thumb_up", "\uD83D\uDC4D 엄지 척 (엄지만 폄)", "손을 제자리에 멈추고 엄지만 편 채 0.6초 유지", AirTouchAction.MEDIA_PLAY_PAUSE),
    FIST("pose_fist", "\u270A 주먹 쥐기 (5개 모두 접음)", "다섯 손가락을 모두 쥔 채 0.6초 유지", AirTouchAction.TOGGLE_PAUSE_TRACKING)
}

class LowRamConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("airtouch_lowram_prefs", Context.MODE_PRIVATE)
    var cameraWidth: Int get() = prefs.getInt("camera_width_v26", 480); set(v) = prefs.edit().putInt("camera_width_v26", v).apply()
    var cameraHeight: Int get() = prefs.getInt("camera_height_v26", 640); set(v) = prefs.edit().putInt("camera_height_v26", v).apply()
    var activeFps: Int get() = prefs.getInt("active_fps_v26", 30); set(v) = prefs.edit().putInt("active_fps_v26", v.coerceIn(15, 30)).apply()
    var idleFps: Int get() = prefs.getInt("idle_fps", 6); set(v) = prefs.edit().putInt("idle_fps", v.coerceIn(2, 15)).apply()
    var useGpuDelegate: Boolean get() = prefs.getBoolean("use_gpu_v22", true); set(v) = prefs.edit().putBoolean("use_gpu_v22", v).apply()
    var cursorSpeed: Float get() = prefs.getFloat("cursor_speed_v26", 1.75f); set(v) = prefs.edit().putFloat("cursor_speed_v26", v.coerceIn(0.6f, 3.2f)).apply()
    var relativeMouseMode: Boolean get() = prefs.getBoolean("relative_mouse_mode_v26", true); set(v) = prefs.edit().putBoolean("relative_mouse_mode_v26", v).apply()
    var mouseAcceleration: Boolean get() = prefs.getBoolean("mouse_accel_v22", true); set(v) = prefs.edit().putBoolean("mouse_accel_v22", v).apply()
    var distanceAutoScale: Boolean get() = prefs.getBoolean("dist_auto_scale_v23", true); set(v) = prefs.edit().putBoolean("dist_auto_scale_v23", v).apply()
    var sniperMicroAim: Boolean get() = prefs.getBoolean("sniper_micro_aim_v23", true); set(v) = prefs.edit().putBoolean("sniper_micro_aim_v23", v).apply()
    var showOverlayStatusPill: Boolean get() = prefs.getBoolean("show_overlay_status_pill", true); set(v) = prefs.edit().putBoolean("show_overlay_status_pill", v).apply()
    var showSkeletonMiniView: Boolean get() = prefs.getBoolean("show_skeleton_mini_v26", true); set(v) = prefs.edit().putBoolean("show_skeleton_mini_v26", v).apply()
    var showLiveMonitorCard: Boolean get() = prefs.getBoolean("show_live_monitor_card", true); set(v) = prefs.edit().putBoolean("show_live_monitor_card", v).apply()
    var pinchRatioThreshold: Float get() = prefs.getFloat("pinch_ratio_v26", 0.32f); set(v) = prefs.edit().putFloat("pinch_ratio_v26", v.coerceIn(0.18f, 0.48f)).apply()
    var dwellClickEnabled: Boolean get() = prefs.getBoolean("dwell_click_enabled", false); set(v) = prefs.edit().putBoolean("dwell_click_enabled", v).apply()
    var dwellDurationMs: Long get() = prefs.getLong("dwell_duration_ms", 1000L); set(v) = prefs.edit().putLong("dwell_duration_ms", v.coerceIn(500L, 2000L)).apply()
    var poseHoldDurationMs: Long get() = prefs.getLong("pose_hold_ms", 600L); set(v) = prefs.edit().putLong("pose_hold_ms", v.coerceIn(300L, 1500L)).apply()
    var swipeScrollEnabled: Boolean get() = prefs.getBoolean("swipe_scroll_enabled", true); set(v) = prefs.edit().putBoolean("swipe_scroll_enabled", v).apply()
    var palmSwipeThreshold: Float get() = prefs.getFloat("palm_swipe_thresh_v27", 0.078f); set(v) = prefs.edit().putFloat("palm_swipe_thresh_v27", v.coerceIn(0.045f, 0.160f)).apply()
    var invertPalmScroll: Boolean get() = prefs.getBoolean("invert_palm_scroll_v27", false); set(v) = prefs.edit().putBoolean("invert_palm_scroll_v27", v).apply()
    fun getActionForPose(pose: HandPose): AirTouchAction = runCatching { AirTouchAction.valueOf(prefs.getString(pose.key, pose.defaultAction.name)!!) }.getOrDefault(pose.defaultAction)
    fun setActionForPose(pose: HandPose, action: AirTouchAction) = prefs.edit().putString(pose.key, action.name).apply()
}

object AirTouchTelemetry {
    @Volatile var handDetected: Boolean = false
    @Volatile var fingerStatesText: String = "손 대기 중..."
    @Volatile var currentModeText: String = "카메라 앞에 손을 보여주세요"
    @Volatile var pinchRatio: Float = 1.0f
    @Volatile var estimatedDistanceCm: Int = 35
    @Volatile var distanceGainFactor: Float = 1.0f
    @Volatile var orientationText: String = "세로 모드"
    @Volatile var cameraResolutionText: String = "대기 중"
    @Volatile var lastTriggeredActionText: String = "없음"
    @Volatile var inferenceLatencyMs: Long = 0L
    @Volatile var scrollDownCount: Int = 0
    @Volatile var scrollUpCount: Int = 0
    @Volatile var swipeSideCount: Int = 0
}

class StableOneEuroFilter(
    private val minCutoffHz: Float = 1.45f,
    private val beta: Float = 16.0f,
    private val dCutoffHz: Float = 10.0f
) {
    private var initialized = false
    var xHat = 0.5f; private set
    var yHat = 0.5f; private set
    private var dxHat = 0f; private var dyHat = 0f; private var lastTimeMs = 0L

    fun reset(x: Float, y: Float, nowMs: Long) {
        initialized = true; xHat = x; yHat = y; dxHat = 0f; dyHat = 0f; lastTimeMs = nowMs
    }

    fun filter(rawX: Float, rawY: Float, nowMs: Long): Pair<Float, Float> {
        if (!initialized || lastTimeMs == 0L || (nowMs - lastTimeMs) > 400L) {
            reset(rawX, rawY, nowMs); return Pair(rawX, rawY)
        }
        val dt = ((nowMs - lastTimeMs).coerceIn(5L, 120L)) / 1000f
        lastTimeMs = nowMs
        val maxJump = 0.25f
        val clampedX = xHat + (rawX - xHat).coerceIn(-maxJump, maxJump)
        val clampedY = yHat + (rawY - yHat).coerceIn(-maxJump, maxJump)
        val dx = (clampedX - xHat) / dt
        val dy = (clampedY - yHat) / dt
        val aDeriv = smoothingFactor(dt, dCutoffHz)
        dxHat += aDeriv * (dx - dxHat)
        dyHat += aDeriv * (dy - dyHat)
        val speed = hypot(dxHat, dyHat)
        val aPos = smoothingFactor(dt, minCutoffHz + beta * speed).coerceIn(0.05f, 0.90f)
        xHat += aPos * (clampedX - xHat)
        yHat += aPos * (clampedY - yHat)
        return Pair(xHat, yHat)
    }

    private fun smoothingFactor(dt: Float, cutoffHz: Float): Float {
        val r = 2.0f * PI.toFloat() * cutoffHz * dt
        return r / (r + 1.0f)
    }
}

class GestureClassifier(private val config: LowRamConfig) {
    data class FrameEvaluation(
        val normCursorX: Float, val normCursorY: Float, val isPinching: Boolean, val pinchProgress: Float,
        val pinchJustClicked: Boolean, val pinchLongPressed: Boolean, val dwellJustClicked: Boolean, val dwellProgress: Float,
        val detectedPose: HandPose?, val poseHoldProgress: Float, val triggeredPoseAction: AirTouchAction?,
        val triggeredSwipeAction: AirTouchAction?, val swipeStrength: Float, val activeScrollBadge: String,
        val trackingPaused: Boolean, val nearFrameEdge: Boolean,
        val estimatedCm: Int, val landmarksXY: FloatArray, val modeSummary: String
    )

    private val fingerFilter = StableOneEuroFilter(minCutoffHz = 1.50f, beta = 16.5f)
    private val knuckleFilter = StableOneEuroFilter(minCutoffHz = 1.20f, beta = 12.0f)
    private val palmFilter = StableOneEuroFilter(minCutoffHz = 2.20f, beta = 20.0f)
    @Volatile private var cursorX = 0.5f; @Volatile private var cursorY = 0.5f
    private var prevFingerX = 0.5f; private var prevFingerY = 0.5f
    private var prevKnuckleX = 0.5f; private var prevKnuckleY = 0.5f
    private var lastValidEvalMs = 0L
    private var handEnteredFrameMs = 0L
    private var smoothedHandScale2D = 0.18f; private var handScaleInitialized = false
    private val fingerScore = FloatArray(5) { 0f }
    private val fingerState = BooleanArray(5) { false }
    private var prevPinchRatio = 1.0f; private var isCurrentlyPinching = false; private var pinchStartTimeMs = 0L
    private var longPressTriggeredInCurrentPinch = false; private var postPinchFreezeUntilMs = 0L
    private var dwellAnchorX = 0.5f; private var dwellAnchorY = 0.5f; private var dwellStartTimeMs = 0L; private var dwellTriggeredAtCurrentSpot = false
    private var currentCandidatePose: HandPose? = null; private var poseStartTimeMs = 0L; private var poseTriggeredInCurrentHold = false

    private val bufSize = 12
    private val palmBufX = FloatArray(bufSize)
    private val palmBufY = FloatArray(bufSize)
    private val palmBufCurX = FloatArray(bufSize)
    private val palmBufCurY = FloatArray(bufSize)
    private val palmBufTs = LongArray(bufSize)
    private var palmBufHead = 0
    private var palmBufCount = 0
    private var prevSmoothPalmX = -1f; private var prevSmoothPalmY = -1f
    private var lastScrollHandOpenMs = 0L
    private var lastSwipeTriggerTimeMs = 0L
    private var lastSwipeAction: AirTouchAction? = null
    private var scrollBadgeUntilMs = 0L
    private var scrollBadgeLabel = ""

    private val exportedLandmarks = FloatArray(42)
    var isTrackingPaused: Boolean = false; private set

    fun recenterCursor() { cursorX = 0.5f; cursorY = 0.5f }

    fun evaluate(lm: List<NormalizedLandmark>): FrameEvaluation {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until minOf(21, lm.size)) {
            exportedLandmarks[i * 2] = lm[i].x(); exportedLandmarks[i * 2 + 1] = lm[i].y()
        }
        val wrist = lm[0]; val thumbTip = lm[4]; val indexMcp = lm[5]; val indexPip = lm[6]; val indexTip = lm[8]; val middleMcp = lm[9]; val pinkyMcp = lm[17]

        val rawScale2D = maxOf(dist2D(wrist, middleMcp), dist2D(wrist, indexMcp) * 0.95f, dist2D(indexMcp, pinkyMcp) * 1.18f).coerceIn(0.06f, 0.55f)
        if (!handScaleInitialized || (now - lastValidEvalMs) > 600L) {
            smoothedHandScale2D = rawScale2D; handScaleInitialized = true
        } else {
            smoothedHandScale2D += (rawScale2D - smoothedHandScale2D) * 0.10f
        }
        val handScale = smoothedHandScale2D
        val estCm = (6.4f / handScale).roundToInt().coerceIn(15, 95)
        AirTouchTelemetry.estimatedDistanceCm = estCm

        val distGain = if (config.distanceAutoScale) (0.185f / handScale).coerceIn(0.65f, 1.95f) else 1.0f
        AirTouchTelemetry.distanceGainFactor = distGain

        val nearEdge = indexMcp.x() !in 0.03f..0.97f || indexMcp.y() !in 0.03f..0.97f || indexTip.x() !in 0.01f..0.99f || indexTip.y() !in 0.01f..0.99f
        val rawFingerX = indexTip.x() * 0.45f + indexPip.x() * 0.35f + indexMcp.x() * 0.20f
        val rawFingerY = indexTip.y() * 0.45f + indexPip.y() * 0.35f + indexMcp.y() * 0.20f
        val (smoothFingerX, smoothFingerY) = fingerFilter.filter(rawFingerX, rawFingerY, now)
        val (smoothKnuckleX, smoothKnuckleY) = knuckleFilter.filter(indexMcp.x(), indexMcp.y(), now)

        val rawPalmX = wrist.x() * 0.30f + indexMcp.x() * 0.25f + middleMcp.x() * 0.25f + pinkyMcp.x() * 0.20f
        val rawPalmY = wrist.y() * 0.30f + indexMcp.y() * 0.25f + middleMcp.y() * 0.25f + pinkyMcp.y() * 0.20f
        val (palmX, palmY) = palmFilter.filter(rawPalmX, rawPalmY, now)

        val handReentered = (now - lastValidEvalMs) > 220L
        if (handReentered) {
            handEnteredFrameMs = now
            prevFingerX = smoothFingerX; prevFingerY = smoothFingerY
            prevKnuckleX = smoothKnuckleX; prevKnuckleY = smoothKnuckleY
            prevSmoothPalmX = palmX; prevSmoothPalmY = palmY
            prevPinchRatio = dist2D(thumbTip, indexTip) / handScale
            palmBufCount = 0; palmBufHead = 0
        }
        val dtSec = ((now - lastValidEvalMs).coerceIn(8L, 100L)) / 1000f
        lastValidEvalMs = now

        val pinchRatio = dist2D(thumbTip, indexTip) / handScale
        val pinchClosingRate = prevPinchRatio - pinchRatio
        prevPinchRatio = pinchRatio
        AirTouchTelemetry.pinchRatio = pinchRatio

        updateFingerHysteresis(0, isThumbExtendedRaw(lm, handScale))
        updateFingerHysteresis(1, isFingerExtendedRaw(wrist, lm[5], lm[6], lm[7], lm[8]))
        updateFingerHysteresis(2, isFingerExtendedRaw(wrist, lm[9], lm[10], lm[11], lm[12]))
        updateFingerHysteresis(3, isFingerExtendedRaw(wrist, lm[13], lm[14], lm[15], lm[16]))
        updateFingerHysteresis(4, isFingerExtendedRaw(wrist, lm[17], lm[18], lm[19], lm[20]))

        val thumbUp = fingerState[0]; val indexUp = fingerState[1]; val middleUp = fingerState[2]; val ringUp = fingerState[3]; val pinkyUp = fingerState[4]
        AirTouchTelemetry.fingerStatesText = "엄지:${if (thumbUp) "O" else "X"} 검지:${if (indexUp) "O" else "X"} 중지:${if (middleUp) "O" else "X"} 약지:${if (ringUp) "O" else "X"} 새끼:${if (pinkyUp) "O" else "X"}"

        val ratioThreshold = config.pinchRatioThreshold
        val isTightFist = !middleUp && !ringUp && !pinkyUp && (dist2D(indexTip, indexMcp) / handScale < 0.36f)
        val pinchActive = !isTightFist && (if (isCurrentlyPinching) pinchRatio < (ratioThreshold * 1.26f) else pinchRatio < ratioThreshold)

        val pinchFreeze = isCurrentlyPinching || pinchActive || (now < postPinchFreezeUntilMs) ||
            (config.sniperMicroAim && pinchRatio < ratioThreshold * 1.15f) ||
            (config.sniperMicroAim && pinchClosingRate > 0.015f && pinchRatio < ratioThreshold * 1.38f)

        if (!isTrackingPaused) {
            if (config.relativeMouseMode) {
                val dFingerX = smoothFingerX - prevFingerX; val dFingerY = smoothFingerY - prevFingerY
                val dKnuckleX = smoothKnuckleX - prevKnuckleX; val dKnuckleY = smoothKnuckleY - prevKnuckleY
                if (!handReentered && !pinchFreeze) {
                    val nearPinchBlend = if (config.sniperMicroAim && pinchRatio < ratioThreshold * 1.38f) {
                        ((pinchRatio - ratioThreshold * 1.15f) / (ratioThreshold * 0.23f)).coerceIn(0.18f, 1.0f)
                    } else 1.0f
                    val rawDx = dFingerX * nearPinchBlend + dKnuckleX * (1f - nearPinchBlend) * 0.5f
                    val rawDy = dFingerY * nearPinchBlend + dKnuckleY * (1f - nearPinchBlend) * 0.5f
                    val mag = hypot(rawDx, rawDy)
                    val softKnee = mag / (mag + 0.00070f)
                    val normVel = (mag / dtSec) * distGain
                    val accel = if (config.mouseAcceleration && nearPinchBlend > 0.7f) 1.0f + (normVel * 0.55f).coerceAtMost(1.35f) else 1.0f
                    val edgeFactor = if (nearEdge) 0.35f else 1.0f
                    val gain = config.cursorSpeed * 1.90f * distGain * softKnee * accel * nearPinchBlend * edgeFactor
                    cursorX = (cursorX + rawDx * gain).coerceIn(0f, 1f)
                    cursorY = (cursorY + rawDy * gain * 1.08f).coerceIn(0f, 1f)
                }
            } else if (!pinchFreeze) {
                val absRawX = smoothKnuckleX * 0.55f + smoothFingerX * 0.45f
                val absRawY = smoothKnuckleY * 0.55f + smoothFingerY * 0.45f
                val targetX = ((absRawX - 0.50f) * (config.cursorSpeed * 1.15f) + 0.50f).coerceIn(0f, 1f)
                val targetY = ((absRawY - 0.54f) * (config.cursorSpeed * 1.25f) + 0.50f).coerceIn(0f, 1f)
                cursorX += (targetX - cursorX) * 0.55f; cursorY += (targetY - cursorY) * 0.55f
            }
        }
        prevFingerX = smoothFingerX; prevFingerY = smoothFingerY
        prevKnuckleX = smoothKnuckleX; prevKnuckleY = smoothKnuckleY

        var pinchJustClicked = false; var pinchLongPressed = false; var pinchProgress = 0f
        if (pinchActive) {
            if (!isCurrentlyPinching) {
                isCurrentlyPinching = true; pinchStartTimeMs = now; longPressTriggeredInCurrentPinch = false
            } else {
                val heldMs = now - pinchStartTimeMs
                pinchProgress = (heldMs.toFloat() / 650f).coerceIn(0f, 1f)
                if (!longPressTriggeredInCurrentPinch && heldMs >= 650L) {
                    if (!isTrackingPaused) {
                        pinchLongPressed = true; postPinchFreezeUntilMs = now + 220L
                        AirTouchTelemetry.lastTriggeredActionText = "길게 누르기 (Long Press)"
                    }
                    longPressTriggeredInCurrentPinch = true
                }
            }
        } else if (isCurrentlyPinching) {
            val heldMs = now - pinchStartTimeMs
            if (!longPressTriggeredInCurrentPinch && heldMs in 18L..649L && !isTrackingPaused) {
                pinchJustClicked = true; postPinchFreezeUntilMs = now + 220L
                AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 클릭 성공! (Pinch Tap)"
            }
            isCurrentlyPinching = false
        }

        val isScrollHandOpenNow = indexUp && (middleUp || ringUp) && !isCurrentlyPinching && !pinchActive && (pinchRatio > ratioThreshold * 1.38f)
        if (isScrollHandOpenNow) lastScrollHandOpenMs = now

        val instStepX = if (prevSmoothPalmX >= 0f) (palmX - prevSmoothPalmX) else 0f
        val instStepY = if (prevSmoothPalmY >= 0f) (palmY - prevSmoothPalmY) else 0f
        val palmSpeedNorm = (hypot(instStepX, instStepY) / dtSec) * distGain
        prevSmoothPalmX = palmX; prevSmoothPalmY = palmY

        palmBufX[palmBufHead] = palmX
        palmBufY[palmBufHead] = palmY
        palmBufCurX[palmBufHead] = cursorX
        palmBufCurY[palmBufHead] = cursorY
        palmBufTs[palmBufHead] = now
        palmBufHead = (palmBufHead + 1) % bufSize
        if (palmBufCount < bufSize) palmBufCount++

        var triggeredSwipeAction: AirTouchAction? = null
        var swipeStrength = 1.0f
        val canEvaluateSwipe = config.swipeScrollEnabled &&
            !isTrackingPaused &&
            !isCurrentlyPinching &&
            !pinchFreeze &&
            (now - handEnteredFrameMs) > 160L &&
            (now - lastScrollHandOpenMs) <= 260L &&
            (now - lastSwipeTriggerTimeMs) > 290L

        if (canEvaluateSwipe && palmBufCount >= 2) {
            var bestDx = 0f; var bestDy = 0f; var bestMag = 0f; var bestDtMs = 100L
            var anchorCurX = cursorX; var anchorCurY = cursorY
            for (k in 1 until palmBufCount) {
                val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
                val dtWin = now - palmBufTs[idx]
                if (dtWin < 45L) continue
                if (dtWin > 300L) break
                val dx = (palmX - palmBufX[idx]) * distGain
                val dy = (palmY - palmBufY[idx]) * distGain
                val mag = hypot(dx, dy)
                if (mag > bestMag) {
                    bestMag = mag; bestDx = dx; bestDy = dy; bestDtMs = dtWin
                    anchorCurX = palmBufCurX[idx]; anchorCurY = palmBufCurY[idx]
                }
            }

            val baseThresh = config.palmSwipeThreshold
            val winSec = (bestDtMs.coerceAtLeast(45L)) / 1000f
            val avgSpeed = bestMag / winSec

            if (bestMag >= baseThresh && avgSpeed >= 0.34f) {
                var candAction: AirTouchAction? = null
                var domDisp = 0f
                if (abs(bestDy) >= baseThresh && abs(bestDy) > abs(bestDx) * 1.15f && (bestDy * instStepY >= -0.002f)) {
                    val moveUp = bestDy < 0f
                    candAction = if (!config.invertPalmScroll) {
                        if (moveUp) AirTouchAction.SCROLL_DOWN else AirTouchAction.SCROLL_UP
                    } else {
                        if (moveUp) AirTouchAction.SCROLL_UP else AirTouchAction.SCROLL_DOWN
                    }
                    domDisp = abs(bestDy)
                } else if (abs(bestDx) >= baseThresh * 1.08f && abs(bestDx) > abs(bestDy) * 1.22f && (bestDx * instStepX >= -0.002f)) {
                    candAction = if (bestDx < 0f) AirTouchAction.SWIPE_LEFT else AirTouchAction.SWIPE_RIGHT
                    domDisp = abs(bestDx)
                }

                if (candAction != null) {
                    val sinceLast = now - lastSwipeTriggerTimeMs
                    val isOppositeReturn = (lastSwipeAction == AirTouchAction.SCROLL_DOWN && candAction == AirTouchAction.SCROLL_UP) ||
                        (lastSwipeAction == AirTouchAction.SCROLL_UP && candAction == AirTouchAction.SCROLL_DOWN) ||
                        (lastSwipeAction == AirTouchAction.SWIPE_LEFT && candAction == AirTouchAction.SWIPE_RIGHT) ||
                        (lastSwipeAction == AirTouchAction.SWIPE_RIGHT && candAction == AirTouchAction.SWIPE_LEFT)

                    val passReturnGuard = !isOppositeReturn || sinceLast > 850L || (domDisp >= baseThresh * 1.65f && avgSpeed >= 0.72f)
                    if (passReturnGuard) {
                        triggeredSwipeAction = candAction
                        swipeStrength = (domDisp / baseThresh).coerceIn(1.0f, 1.85f)
                        lastSwipeTriggerTimeMs = now
                        lastSwipeAction = candAction
                        cursorX = anchorCurX; cursorY = anchorCurY
                        palmBufCount = 0; palmBufHead = 0
                        currentCandidatePose = null; poseTriggeredInCurrentHold = false
                        when (candAction) {
                            AirTouchAction.SCROLL_DOWN -> { AirTouchTelemetry.scrollDownCount++; scrollBadgeLabel = "▼ 아래 스크롤" }
                            AirTouchAction.SCROLL_UP -> { AirTouchTelemetry.scrollUpCount++; scrollBadgeLabel = "▲ 위로 스크롤" }
                            AirTouchAction.SWIPE_LEFT -> { AirTouchTelemetry.swipeSideCount++; scrollBadgeLabel = "◀ 왼쪽 스와이프" }
                            AirTouchAction.SWIPE_RIGHT -> { AirTouchTelemetry.swipeSideCount++; scrollBadgeLabel = "▶ 오른쪽 스와이프" }
                            else -> {}
                        }
                        scrollBadgeUntilMs = now + 520L
                        AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDD90\uFE0F ${candAction.titleKo}"
                    }
                }
            }
        }

        var dwellProgress = 0f; var dwellJustClicked = false
        if (config.dwellClickEnabled && !isTrackingPaused && !isCurrentlyPinching && indexUp && (now - lastSwipeTriggerTimeMs) > 600L) {
            if (hypot(cursorX - dwellAnchorX, cursorY - dwellAnchorY) > 0.018f) {
                dwellAnchorX = cursorX; dwellAnchorY = cursorY; dwellStartTimeMs = now; dwellTriggeredAtCurrentSpot = false
            } else {
                val elapsed = now - dwellStartTimeMs
                dwellProgress = (elapsed.toFloat() / config.dwellDurationMs.toFloat()).coerceIn(0f, 1f)
                if (!dwellTriggeredAtCurrentSpot && elapsed >= config.dwellDurationMs) {
                    dwellTriggeredAtCurrentSpot = true; dwellJustClicked = true
                    AirTouchTelemetry.lastTriggeredActionText = "머물러 자동 클릭! (Dwell Tap)"
                }
            }
        } else { dwellStartTimeMs = now; dwellTriggeredAtCurrentSpot = false }

        val isPointingCursor = indexUp && !middleUp && !ringUp && !pinkyUp
        val rawPose = if (isCurrentlyPinching || pinchFreeze || isPointingCursor || pinchRatio < ratioThreshold * 1.35f || (now - lastSwipeTriggerTimeMs) < 1000L) null else when {
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
        if (rawPose != null && palmSpeedNorm < 0.13f) {
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
        } else if (palmSpeedNorm >= 0.16f || rawPose == null) { currentCandidatePose = null; poseTriggeredInCurrentHold = false }

        val activeBadge = if (now < scrollBadgeUntilMs) scrollBadgeLabel else ""
        val summary = when {
            isTrackingPaused -> "[일시정지] \u270A 주먹 0.6초 쥐어 해제"
            activeBadge.isNotEmpty() -> "\uD83D\uDD90\uFE0F $activeBadge 실행됨!"
            nearEdge -> "손을 카메라 화면 안쪽으로 살짝 옮겨주세요 (${estCm}cm)"
            isCurrentlyPinching -> "\uD83D\uDC4C 클릭 고정됨 (${estCm}cm · 손가락 떼면 클릭)"
            poseProgress > 0.12f && currentCandidatePose != null -> "${currentCandidatePose!!.labelKo} (${(poseProgress * 100).toInt()}%)"
            isScrollHandOpenNow -> "\uD83D\uDD90\uFE0F 손바닥 스크롤 대기/포인터 (${estCm}cm · 상하좌우로 쓸어보세요)"
            else -> "◎ 정밀 포인터 추적 중 (${estCm}cm · 엄지+검지 맞대면 클릭)"
        }
        AirTouchTelemetry.currentModeText = summary
        return FrameEvaluation(cursorX, cursorY, isCurrentlyPinching, pinchProgress, pinchJustClicked, pinchLongPressed, dwellJustClicked, dwellProgress, currentCandidatePose, poseProgress, triggeredPoseAction, triggeredSwipeAction, swipeStrength, activeBadge, isTrackingPaused, nearEdge, estCm, exportedLandmarks, summary)
    }

    private fun updateFingerHysteresis(idx: Int, rawExtended: Boolean) {
        fingerScore[idx] += ((if (rawExtended) 1.0f else 0.0f) - fingerScore[idx]) * 0.52f
        fingerState[idx] = if (fingerState[idx]) fingerScore[idx] >= 0.36f else fingerScore[idx] >= 0.62f
    }

    private fun isFingerExtendedRaw(wrist: NormalizedLandmark, mcp: NormalizedLandmark, pip: NormalizedLandmark, dip: NormalizedLandmark, tip: NormalizedLandmark): Boolean {
        val dTip = dist2D(wrist, tip); val dDip = dist2D(wrist, dip); val dPip = dist2D(wrist, pip); val dMcp = dist2D(wrist, mcp)
        if (dTip <= dPip * 1.05f || dTip <= dMcp * 1.18f || dDip <= dMcp * 1.05f) return false
        val v1x = pip.x() - mcp.x(); val v1y = pip.y() - mcp.y(); val v2x = tip.x() - pip.x(); val v2y = tip.y() - pip.y()
        val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y).coerceAtLeast(1e-4f) * hypot(v2x, v2y).coerceAtLeast(1e-4f))
        return cos > 0.35f
    }

    private fun isThumbExtendedRaw(lm: List<NormalizedLandmark>, handScale: Float): Boolean {
        val wrist = lm[0]; val cmc = lm[1]; val mcp = lm[2]; val ip = lm[3]; val tip = lm[4]
        val v1x = ip.x() - cmc.x(); val v1y = ip.y() - cmc.y(); val v2x = tip.x() - mcp.x(); val v2y = tip.y() - mcp.y()
        val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y).coerceAtLeast(1e-4f) * hypot(v2x, v2y).coerceAtLeast(1e-4f))
        return (dist2D(tip, lm[5]) / handScale > 0.48f) && (dist2D(tip, lm[9]) / handScale > 0.58f) && (dist2D(wrist, tip) > dist2D(wrist, mcp) * 1.10f) && cos > 0.42f
    }

    private fun dist2D(a: NormalizedLandmark, b: NormalizedLandmark): Float = hypot(a.x() - b.x(), a.y() - b.y())
}

class CursorOverlayView(context: Context) : View(context) {
    companion object {
        private val HAND_BONES = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8,
            5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16,
            0, 17, 13, 17, 17, 18, 18, 19, 19, 20
        )
    }
    private val density = context.resources.displayMetrics.density
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.parseColor("#00E676") }
    private val cursorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; color = Color.parseColor("#66000000") }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00B0FF") }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#CC101418") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13.5f * density; typeface = Typeface.DEFAULT_BOLD }
    private val cursorBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); textSize = 12.5f * density; typeface = Typeface.DEFAULT_BOLD }
    private val miniBoxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#B80D1117") }
    private val miniBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.parseColor("#00E676") }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00E676") }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val keyJointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#00B0FF") }
    private val miniTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 10.5f * density; typeface = Typeface.MONOSPACE }
    private val arcRect = RectF(); private val pillRect = RectF(); private val miniRect = RectF(); private val scrollTagRect = RectF()
    private val lmPts = FloatArray(42)

    var targetX = 540f; var targetY = 960f; var renderX = 540f; var renderY = 960f
    private var lastDrawMs = 0L
    var handVisible = false; var nearEdge = false; var estimatedCm = 35
    var isPinching = false; var isPaused = false; var gaugeProgress = 0f; var statusLabel = "AirTouch 대기 중"
    var scrollFeedbackBadge = ""
    var showStatusPill = true; var showSkeletonMini = true

    fun updateState(x: Float, y: Float, handFound: Boolean, edgeWarn: Boolean, estCm: Int, landmarks: FloatArray?, pinching: Boolean, paused: Boolean, progress: Float, label: String, scrollBadge: String, showPill: Boolean, showMini: Boolean) {
        targetX = x; targetY = y; handVisible = handFound; nearEdge = edgeWarn; estimatedCm = estCm
        if (landmarks != null && landmarks.size >= 42) System.arraycopy(landmarks, 0, lmPts, 0, 42)
        isPinching = pinching; isPaused = paused; gaugeProgress = progress; statusLabel = label; scrollFeedbackBadge = scrollBadge
        showStatusPill = showPill; showSkeletonMini = showMini
        if (pinching || hypot(targetX - renderX, targetY - renderY) > 360f) { renderX = targetX; renderY = targetY }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastDrawMs).coerceIn(4L, 40L)) / 1000f
        lastDrawMs = now
        val dx = targetX - renderX; val dy = targetY - renderY
        if (isPinching || hypot(dx, dy) < 0.35f) {
            renderX = targetX; renderY = targetY
        } else {
            val blend = (1.0f - exp(-36.0f * dt)).coerceIn(0.18f, 0.85f)
            renderX += dx * blend; renderY += dy * blend
            postInvalidateOnAnimation()
        }
        val r = if (isPinching) 20f else 28f
        outerRingPaint.color = when {
            isPaused -> Color.parseColor("#FF5252")
            scrollFeedbackBadge.isNotEmpty() -> Color.parseColor("#00E5FF")
            isPinching -> Color.parseColor("#FFD740")
            else -> Color.parseColor("#00E676")
        }
        canvas.drawCircle(renderX, renderY, r, cursorShadowPaint)
        canvas.drawCircle(renderX, renderY, r, outerRingPaint)
        canvas.drawCircle(renderX, renderY, if (isPinching) 10.5f else 5.5f, centerDotPaint)
        if (gaugeProgress > 0.04f) {
            arcRect.set(renderX - r - 11f, renderY - r - 11f, renderX + r + 11f, renderY + r + 11f)
            canvas.drawArc(arcRect, -90f, 360f * gaugeProgress, false, progressArcPaint)
        }
        if (scrollFeedbackBadge.isNotEmpty()) {
            val tw = cursorBadgePaint.measureText(scrollFeedbackBadge)
            val bx = (renderX - tw * 0.5f - 10f * density).coerceIn(8f, (width - tw - 28f * density).coerceAtLeast(8f))
            val by = (renderY - r - 36f * density).coerceAtLeast(12f * density)
            scrollTagRect.set(bx, by, bx + tw + 20f * density, by + 24f * density)
            canvas.drawRoundRect(scrollTagRect, 12f * density, 12f * density, pillBgPaint)
            canvas.drawText(scrollFeedbackBadge, bx + 10f * density, by + 17f * density, cursorBadgePaint)
        }
        if (showStatusPill && statusLabel.isNotEmpty()) {
            val tw = labelPaint.measureText(statusLabel); val pw = tw + 36f * density
            val pl = ((width - pw) * 0.5f).coerceAtLeast(12f * density)
            val topY = 38f * density
            pillRect.set(pl, topY, pl + pw, topY + 30f * density)
            canvas.drawRoundRect(pillRect, 16f * density, 16f * density, pillBgPaint)
            canvas.drawText(statusLabel, pl + 18f * density, topY + 20f * density, labelPaint)
        }
        if (showSkeletonMini) drawHandSkeletonMiniHud(canvas)
    }

    private fun drawHandSkeletonMiniHud(canvas: Canvas) {
        val boxW = 108f * density; val boxH = 138f * density
        val left = (width - boxW - 14f * density).coerceAtLeast(8f)
        val top = (height - boxH - 84f * density).coerceAtLeast(8f)
        miniRect.set(left, top, left + boxW, top + boxH)
        miniBoxBorderPaint.color = when {
            !handVisible -> Color.parseColor("#546E7A")
            scrollFeedbackBadge.isNotEmpty() -> Color.parseColor("#00E5FF")
            nearEdge -> Color.parseColor("#FFA726")
            isPinching -> Color.parseColor("#FFD740")
            else -> Color.parseColor("#00E676")
        }
        canvas.drawRoundRect(miniRect, 12f * density, 12f * density, miniBoxBgPaint)
        canvas.drawRoundRect(miniRect, 12f * density, 12f * density, miniBoxBorderPaint)
        if (!handVisible) {
            val msg = "손 미감지"; val mw = miniTextPaint.measureText(msg)
            canvas.drawText(msg, left + (boxW - mw) * 0.5f, top + boxH * 0.52f, miniTextPaint); return
        }
        val pad = 10f * density; val drawW = boxW - pad * 2f; val drawH = boxH - pad * 2f - 14f * density
        bonePaint.color = if (scrollFeedbackBadge.isNotEmpty()) Color.parseColor("#00E5FF") else if (nearEdge) Color.parseColor("#FFA726") else Color.parseColor("#00E676")
        var i = 0
        while (i < HAND_BONES.size) {
            val a = HAND_BONES[i]; val b = HAND_BONES[i + 1]
            canvas.drawLine(left + pad + lmPts[a * 2].coerceIn(0f, 1f) * drawW, top + pad + lmPts[a * 2 + 1].coerceIn(0f, 1f) * drawH, left + pad + lmPts[b * 2].coerceIn(0f, 1f) * drawW, top + pad + lmPts[b * 2 + 1].coerceIn(0f, 1f) * drawH, bonePaint)
            i += 2
        }
        for (j in 0 until 21) {
            val jx = left + pad + lmPts[j * 2].coerceIn(0f, 1f) * drawW
            val jy = top + pad + lmPts[j * 2 + 1].coerceIn(0f, 1f) * drawH
            if (j == 4 || j == 8) {
                keyJointPaint.color = if (isPinching) Color.parseColor("#FFD740") else Color.parseColor("#00B0FF")
                canvas.drawCircle(jx, jy, 3.8f * density, keyJointPaint)
            } else canvas.drawCircle(jx, jy, 2.0f * density, jointPaint)
        }
        val footTxt = if (scrollFeedbackBadge.isNotEmpty()) scrollFeedbackBadge else "관절OK · ${estimatedCm}cm"
        canvas.drawText(footTxt, left + 8f * density, top + boxH - 6f * density, miniTextPaint)
    }
}

class AirTouchAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: AirTouchAccessibilityService? = null; private set }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null; private var cursorView: CursorOverlayView? = null
    private lateinit var config: LowRamConfig
    private var screenWidth = 1080; private var screenHeight = 2400

    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this; config = LowRamConfig(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
        if (AirTouchTrackingService.isRunning) showCursorOverlay()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); updateScreenMetrics() }
    override fun onDestroy() { hideCursorOverlay(); if (instance === this) instance = null; super.onDestroy() }

    private fun updateScreenMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds; screenWidth = b.width(); screenHeight = b.height()
        } else {
            val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels; screenHeight = dm.heightPixels
        }
        AirTouchTelemetry.orientationText = if (screenWidth > screenHeight) "가로 모드 (${screenWidth}x${screenHeight})" else "세로 모드 (${screenWidth}x${screenHeight})"
    }

    fun showCursorOverlay() = mainHandler.post {
        if (cursorView != null) return@post
        val wm = windowManager ?: return@post
        updateScreenMetrics()
        val view = CursorOverlayView(this).apply {
            targetX = screenWidth * 0.5f; targetY = screenHeight * 0.5f; renderX = targetX; renderY = targetY
            showStatusPill = config.showOverlayStatusPill; showSkeletonMini = config.showSkeletonMiniView
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(view, params); cursorView = view }
    }

    fun refreshOverlayVisibility() = mainHandler.post {
        cursorView?.let { it.showStatusPill = config.showOverlayStatusPill; it.showSkeletonMini = config.showSkeletonMiniView; it.postInvalidateOnAnimation() }
    }

    fun centerCursorOverlay() = mainHandler.post {
        updateScreenMetrics()
        cursorView?.let { it.targetX = screenWidth * 0.5f; it.targetY = screenHeight * 0.5f; it.renderX = it.targetX; it.renderY = it.targetY; it.postInvalidateOnAnimation() }
    }

    fun hideCursorOverlay() = mainHandler.post {
        val view = cursorView ?: return@post
        runCatching { windowManager?.removeView(view) }; cursorView = null
    }

    fun notifyNoHandDetected() = mainHandler.post {
        cursorView?.let { it.updateState(it.targetX, it.targetY, false, false, AirTouchTelemetry.estimatedDistanceCm, null, false, false, 0f, "전면 카메라 시야에 손을 보여주세요", "", config.showOverlayStatusPill, config.showSkeletonMiniView) }
    }

    fun updateCursorFromEvaluation(eval: GestureClassifier.FrameEvaluation) = mainHandler.post {
        if (cursorView == null) showCursorOverlay()
        val px = (eval.normCursorX * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val py = (eval.normCursorY * screenHeight).coerceIn(0f, screenHeight.toFloat())
        cursorView?.updateState(px, py, true, eval.nearFrameEdge, eval.estimatedCm, eval.landmarksXY, eval.isPinching, eval.trackingPaused, maxOf(eval.poseHoldProgress, eval.pinchProgress, eval.dwellProgress), eval.modeSummary, eval.activeScrollBadge, config.showOverlayStatusPill, config.showSkeletonMiniView)
        if (eval.pinchJustClicked || eval.dwellJustClicked) performAction(AirTouchAction.TAP_AT_CURSOR, px, py)
        else if (eval.pinchLongPressed) performAction(AirTouchAction.LONG_PRESS_AT_CURSOR, px, py)
        eval.triggeredPoseAction?.let { if (it != AirTouchAction.TOGGLE_PAUSE_TRACKING) performAction(it, px, py) }
        eval.triggeredSwipeAction?.let { performAction(it, px, py, eval.swipeStrength) }
    }

    fun performAction(action: AirTouchAction, x: Float = screenWidth / 2f, y: Float = screenHeight / 2f, strength: Float = 1.0f) {
        val safeX = x.coerceIn(screenWidth * 0.22f, screenWidth * 0.78f)
        val safeY = y.coerceIn(screenHeight * 0.25f, screenHeight * 0.75f)
        val halfSpanY = (0.19f + (strength - 1.0f) * 0.09f).coerceIn(0.19f, 0.28f)
        val scrollDur = (225L - ((strength - 1.0f) * 55f).toLong()).coerceIn(165L, 235L)
        when (action) {
            AirTouchAction.NONE, AirTouchAction.TOGGLE_PAUSE_TRACKING -> Unit
            AirTouchAction.TAP_AT_CURSOR -> dispatchTap(x, y, 45L)
            AirTouchAction.LONG_PRESS_AT_CURSOR -> dispatchTap(x, y, 580L)
            AirTouchAction.DOUBLE_TAP_AT_CURSOR -> { dispatchTap(x, y, 40L); mainHandler.postDelayed({ dispatchTap(x, y, 40L) }, 110L) }
            AirTouchAction.SCROLL_DOWN -> dispatchSwipe(safeX, screenHeight * (0.50f + halfSpanY), safeX, screenHeight * (0.50f - halfSpanY), scrollDur)
            AirTouchAction.SCROLL_UP -> dispatchSwipe(safeX, screenHeight * (0.50f - halfSpanY), safeX, screenHeight * (0.50f + halfSpanY), scrollDur)
            AirTouchAction.SWIPE_LEFT -> dispatchSwipe(screenWidth * 0.76f, safeY, screenWidth * 0.24f, safeY, 200L)
            AirTouchAction.SWIPE_RIGHT -> dispatchSwipe(screenWidth * 0.24f, safeY, screenWidth * 0.76f, safeY, 200L)
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
        const val ACTION_RECENTER = "com.prolauncher.airtouch.ACTION_RECENTER"
        private const val CHANNEL_ID = "airtouch_tracking_channel"
        private const val NOTIFICATION_ID = 4102
        @Volatile var isRunning: Boolean = false; private set
    }
    private lateinit var config: LowRamConfig; private lateinit var classifier: GestureClassifier
    private var cameraExecutor: ExecutorService? = null; private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null; private var handLandmarker: HandLandmarker? = null
    private val transformedRing = arrayOfNulls<Bitmap>(3)
    private val transformedCanvases = arrayOfNulls<Canvas>(3)
    private var ringIndex = 0
    private var fallbackRawBitmap: Bitmap? = null
    private val transformMatrix = Matrix()
    private val paintFilter = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cachedDisplayRotation = -1
    @Volatile private var lastFrameProcessTimeMs = 0L; @Volatile private var lastHandDetectedTimeMs = 0L
    @Volatile private var lastSubmittedTimestampMs = 0L; @Volatile private var inferenceBusy = false; @Volatile private var lastInferenceStartMs = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> runCatching { cameraProvider?.unbindAll() }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> cameraProvider?.let { bindAnalysisUseCase(it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate(); config = LowRamConfig(this); classifier = GestureClassifier(config); cameraExecutor = Executors.newSingleThreadExecutor()
        registerReceiver(screenStateReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rot = getCurrentDisplayRotation()
        if (rot != cachedDisplayRotation) { cachedDisplayRotation = rot; runCatching { imageAnalysisUseCase?.targetRotation = rot } }
    }

    private fun getCurrentDisplayRotation(): Int = runCatching {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") wm.defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RECENTER) { classifier.recenterCursor(); AirTouchAccessibilityService.instance?.centerCursorOverlay(); return START_STICKY }
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
        runCatching { cameraProvider?.unbindAll(); cameraProvider = null; imageAnalysisUseCase = null }
        runCatching { handLandmarker?.close(); handLandmarker = null }
        for (i in transformedRing.indices) { transformedRing[i]?.recycle(); transformedRing[i] = null; transformedCanvases[i] = null }
        fallbackRawBitmap?.recycle(); fallbackRawBitmap = null
        cameraExecutor?.shutdownNow(); cameraExecutor = null
        super.onDestroy()
    }

    private fun initHandLandmarker() {
        if (handLandmarker != null) return
        val delegates = if (config.useGpuDelegate) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        for (delegate in delegates) {
            try {
                val baseBuilder = BaseOptions.builder().setDelegate(delegate)
                val customModel = File(filesDir, "hand_landmarker.task")
                if (customModel.exists() && customModel.length() > 100_000L) {
                    FileInputStream(customModel).use { baseBuilder.setModelAssetBuffer(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, customModel.length())) }
                } else baseBuilder.setModelAssetPath("hand_landmarker.task")
                val opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseBuilder.build()).setNumHands(1)
                    .setMinHandDetectionConfidence(0.55f).setMinHandPresenceConfidence(0.55f).setMinTrackingConfidence(0.52f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener(this::onHandLandmarkResult)
                    .setErrorListener { _ -> inferenceBusy = false }
                    .build()
                handLandmarker = HandLandmarker.createFromOptions(this, opts)
                break
            } catch (_: Exception) {}
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
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(config.cameraWidth, config.cameraHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val rot = getCurrentDisplayRotation(); cachedDisplayRotation = rot
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resSelector).setTargetRotation(rot)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec, this::processCameraFrame)
            imageAnalysisUseCase = analysis
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
    }

    private fun extractCleanFrameBitmap(imageProxy: ImageProxy): Bitmap? {
        runCatching { return imageProxy.toBitmap() }
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val w = imageProxy.width; val h = imageProxy.height
        val pixelStride = plane.pixelStride.coerceAtLeast(4); val rowStride = plane.rowStride
        val buf = plane.buffer; buf.rewind()
        val stridePixels = rowStride / pixelStride
        return if (stridePixels <= w) {
            var bmp = fallbackRawBitmap
            if (bmp == null || bmp.width != w || bmp.height != h) { bmp?.recycle(); bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); fallbackRawBitmap = bmp }
            bmp.copyPixelsFromBuffer(buf); bmp
        } else {
            var padded = fallbackRawBitmap
            if (padded == null || padded.width != stridePixels || padded.height != h) { padded?.recycle(); padded = Bitmap.createBitmap(stridePixels, h, Bitmap.Config.ARGB_8888); fallbackRawBitmap = padded }
            padded.copyPixelsFromBuffer(buf); Bitmap.createBitmap(padded, 0, 0, w, h)
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis(); val landmarker = handLandmarker
        if (landmarker == null) { imageProxy.close(); return }
        if (inferenceBusy && (now - lastInferenceStartMs) < 90L) { imageProxy.close(); return }
        val targetFps = if ((now - lastHandDetectedTimeMs) > 4000L) config.idleFps else config.activeFps
        if ((now - lastFrameProcessTimeMs) < (1000L / targetFps.coerceAtLeast(1))) { imageProxy.close(); return }
        lastFrameProcessTimeMs = now
        try {
            val rawBmp = extractCleanFrameBitmap(imageProxy) ?: return
            try {
                val w = rawBmp.width; val h = rawBmp.height; val rot = imageProxy.imageInfo.rotationDegrees
                val outW = if (rot == 90 || rot == 270) h else w; val outH = if (rot == 90 || rot == 270) w else h
                AirTouchTelemetry.cameraResolutionText = "${outW}x${outH}"
                val slot = ringIndex; ringIndex = (ringIndex + 1) % transformedRing.size
                var tBmp = transformedRing[slot]; var tCanvas = transformedCanvases[slot]
                if (tBmp == null || tBmp.width != outW || tBmp.height != outH) {
                    tBmp?.recycle(); tBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    tCanvas = Canvas(tBmp); transformedRing[slot] = tBmp; transformedCanvases[slot] = tCanvas
                }
                transformMatrix.reset()
                transformMatrix.postTranslate(-w / 2f, -h / 2f)
                if (rot != 0) transformMatrix.postRotate(rot.toFloat())
                transformMatrix.postScale(-1f, 1f)
                transformMatrix.postTranslate(outW / 2f, outH / 2f)
                tCanvas?.drawColor(Color.BLACK)
                tCanvas?.drawBitmap(rawBmp, transformMatrix, paintFilter)
                val safeTs = maxOf(now, lastSubmittedTimestampMs + 1L); lastSubmittedTimestampMs = safeTs
                inferenceBusy = true; lastInferenceStartMs = now
                landmarker.detectAsync(BitmapImageBuilder(tBmp).build(), safeTs)
            } finally {
                if (rawBmp !== fallbackRawBitmap) rawBmp.recycle()
            }
        } catch (_: Exception) { inferenceBusy = false } finally { imageProxy.close() }
    }

    private fun onHandLandmarkResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: MPImage) {
        inferenceBusy = false
        val now = SystemClock.uptimeMillis()
        AirTouchTelemetry.inferenceLatencyMs = (now - result.timestampMs()).coerceAtLeast(1L)
        val hands = result.landmarks()
        if (hands.isNullOrEmpty()) {
            AirTouchTelemetry.handDetected = false; AirTouchTelemetry.fingerStatesText = "손 미감지 (전면 카메라 시야에 손을 들어 올려주세요)"
            AirTouchTelemetry.currentModeText = "손 대기 중..."; AirTouchAccessibilityService.instance?.notifyNoHandDetected()
            return
        }
        AirTouchTelemetry.handDetected = true; lastHandDetectedTimeMs = now
        AirTouchAccessibilityService.instance?.updateCursorFromEvaluation(classifier.evaluate(hands[0]))
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AirTouch 백그라운드 추적", NotificationManager.IMPORTANCE_LOW))
        val stopPending = PendingIntent.getService(this, 1, Intent(this, AirTouchTrackingService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPending = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AirTouch AI v2.7 (정밀 손바닥 스크롤 & 광마우스)").setContentText("다중프레임 손바닥 스크롤 + 스트라이드 보정 VGA + 21관절 뷰")
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
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val pkg = context.packageName; val fullCls = AirTouchAccessibilityService::class.java.name; val shortCls = ".${AirTouchAccessibilityService::class.java.simpleName}"
        return enabled.contains("$pkg/$fullCls") || enabled.contains("$pkg/$shortCls")
    }
    fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, AirTouchTrackingService::class.java).apply { action = AirTouchTrackingService.ACTION_START })
    fun stop(context: Context) { context.startService(Intent(context, AirTouchTrackingService::class.java).apply { action = AirTouchTrackingService.ACTION_STOP }) }
    fun recenter(context: Context) { if (isRunning()) context.startService(Intent(context, AirTouchTrackingService::class.java).apply { action = AirTouchTrackingService.ACTION_RECENTER }) }
    fun toggle(context: Context): Boolean = if (isRunning()) { stop(context); false } else { start(context); true }
    fun openAccessibilitySettings(context: Context) = context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
}

class MainActivity : AppCompatActivity() {
    private lateinit var config: LowRamConfig; private lateinit var statusText: TextView
    private lateinit var monitorHeaderView: TextView; private lateinit var liveTelemetryText: TextView; private lateinit var ramUsageText: TextView
    private lateinit var scrollTestCounterText: TextView
    private lateinit var toggleBtn: Button; private lateinit var practiceBtn: Button
    private var practiceClickCount = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable { override fun run() { updateLiveTelemetryCard(); uiHandler.postDelayed(this, 140L) } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); config = LowRamConfig(this); setContentView(buildUi()) }
    override fun onResume() { super.onResume(); refreshDashboardState(); uiHandler.post(uiRefreshRunnable) }
    override fun onPause() { uiHandler.removeCallbacks(uiRefreshRunnable); super.onPause() }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D0F12")) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(40)) }
        val pInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION") val vCode = pInfo?.versionCode ?: 0
        root.addView(TextView(this).apply { text = "AIRTOUCH AI v2.7 (Build $vCode)"; textSize = 18.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676")) })
        root.addView(TextView(this).apply { text = "다중 프레임 손바닥 스크롤 엔진 + 무손실 VGA 관절 인식 + 블루투스 광마우스"; textSize = 12f; setTextColor(Color.parseColor("#9E9E9E")); setPadding(0, dp(4), 0, dp(14)) })

        val hasLegacyConflictPkg = packageName != "com.prolauncher.airtouch" && runCatching { packageManager.getPackageInfo("com.prolauncher.airtouch", 0); true }.getOrDefault(false)
        if (hasLegacyConflictPkg) {
            val delBtn = createBtn("⚠ [구버전 충돌 앱 감지됨] 터치하여 이전 AirTouch 삭제하기", false) { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:com.prolauncher.airtouch"))) }
            delBtn.setBackgroundColor(Color.parseColor("#B71C1C")); root.addView(delBtn)
        }

        statusText = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#181B20")); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        root.addView(statusText)

        root.addView(createHeader("STATUS OVERLAY & LAYOUT TOGGLE (현재 상태 및 손 관절 미니 뷰 토글)"))
        root.addView(Switch(this).apply {
            text = "화면 우측 하단 21개 손가락 관절 실시간 인식 미니 뷰 표시 (인식 확인 후 끄기 가능)"
            setTextColor(Color.WHITE); isChecked = config.showSkeletonMiniView; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.showSkeletonMiniView = checked; AirTouchAccessibilityService.instance?.refreshOverlayVisibility() }
        })
        root.addView(Switch(this).apply {
            text = "화면 상단 현재 상태 오버레이 배지 표시 (끄면 커서만 깔끔하게 표시)"
            setTextColor(Color.WHITE); isChecked = config.showOverlayStatusPill; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.showOverlayStatusPill = checked; AirTouchAccessibilityService.instance?.refreshOverlayVisibility() }
        })
        root.addView(Switch(this).apply {
            text = "앱 내 실시간 인식 상태 모니터 레이아웃 표시 (ON / OFF)"
            setTextColor(Color.WHITE); isChecked = config.showLiveMonitorCard; setPadding(0, dp(6), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                config.showLiveMonitorCard = checked
                val vis = if (checked) View.VISIBLE else View.GONE
                monitorHeaderView.visibility = vis; liveTelemetryText.visibility = vis
            }
        })

        monitorHeaderView = createHeader("LIVE GESTURE & DISTANCE MONITOR (실시간 관절·스크롤·거리 모니터)")
        liveTelemetryText = TextView(this).apply { textSize = 12.6f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#FFD740")); setBackgroundColor(Color.parseColor("#141920")); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val initVis = if (config.showLiveMonitorCard) View.VISIBLE else View.GONE
        monitorHeaderView.visibility = initVis; liveTelemetryText.visibility = initVis
        root.addView(monitorHeaderView); root.addView(liveTelemetryText)

        scrollTestCounterText = TextView(this).apply {
            text = "\uD83D\uDD90\uFE0F [손바닥 스크롤 실시간 검증] 손바닥을 펴고 위·아래·좌·우로 가볍게(약 4cm) 쓸어보세요!\n  • 아래 스크롤: 0회  |  위로 스크롤: 0회  |  좌·우 스와이프: 0회"
            textSize = 12.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#102028")); setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        root.addView(scrollTestCounterText)

        practiceBtn = createBtn("★ [정밀 클릭 연습 타겟] 커서를 올리고 \uD83D\uDC4C(엄지+검지) 살짝 맞대어 보세요! (성공: 0회)") {
            practiceClickCount++
            practiceBtn.text = "★ [\uD83D\uDC4C 클릭 성공!] 흔들림 없이 정확히 클릭됨! 누적 성공: ${practiceClickCount}회"
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
        root.addView(createBtn("[ 커서 위치 화면 정중앙으로 리셋 ]") { LauncherAirTouchBridge.recenter(this) })

        root.addView(createHeader("PALM SCROLL TUNING (\uD83D\uDD90\uFE0F 손바닥 스크롤 민감도 및 방향 설정)"))
        root.addView(Switch(this).apply {
            text = "\uD83D\uDD90\uFE0F 손바닥 상하좌우 스와이프 스크롤 사용 (손가락 2개 이상 펴고 가볍게 쓸기)"
            setTextColor(Color.WHITE); isChecked = config.swipeScrollEnabled; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.swipeScrollEnabled = checked }
        })
        root.addView(Switch(this).apply {
            text = "손바닥 상하 스크롤 방향 반전 (기본 OFF: 손을 위로 쓸면 화면 아래 내용 보기)"
            setTextColor(Color.WHITE); isChecked = config.invertPalmScroll; setPadding(0, dp(6), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.invertPalmScroll = checked }
        })
        val scrollSensLabel = TextView(this).apply {
            text = "\uD83D\uDD90\uFE0F 손바닥 스크롤 요구 이동폭: ${String.format("%.3f", config.palmSwipeThreshold)} (낮출수록 손목만 까딱해도 스크롤됨)"
            setTextColor(Color.LTGRAY); textSize = 12.5f
        }
        root.addView(scrollSensLabel)
        root.addView(SeekBar(this).apply {
            max = 115; progress = ((config.palmSwipeThreshold - 0.045f) * 1000f).toInt().coerceIn(0, 115)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val v = 0.045f + (p / 1000f)
                    config.palmSwipeThreshold = v
                    scrollSensLabel.text = "\uD83D\uDD90\uFE0F 손바닥 스크롤 요구 이동폭: ${String.format("%.3f", v)} (낮출수록 손목만 까딱해도 스크롤됨)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(createHeader("OPTICAL MOUSE & PRECISION TUNING (블루투스 마우스 정밀 트래킹 설정)"))
        root.addView(Switch(this).apply {
            text = "블루투스 광마우스 상대 이동 모드 (권장: 손가락 미세 이동 추적 + Z축 점프 원천 차단)"
            setTextColor(Color.WHITE); isChecked = config.relativeMouseMode; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.relativeMouseMode = checked }
        })
        root.addView(Switch(this).apply {
            text = "실시간 손 거리(cm) 계산 자동 변위 보정 (멀리서도 동일한 스크롤·이동 감도 유지)"
            setTextColor(Color.WHITE); isChecked = config.distanceAutoScale; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.distanceAutoScale = checked }
        })
        root.addView(Switch(this).apply {
            text = "\uD83D\uDC4C 핀치 클릭 무반동 게이트 (엄지·검지 맞댈 때 커서 밀림 0.0px 차단)"
            setTextColor(Color.WHITE); isChecked = config.sniperMicroAim; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.sniperMicroAim = checked }
        })
        root.addView(Switch(this).apply {
            text = "블루투스 마우스 비선형 가속 (빠른 이동 시 화면 끝까지 부드럽게 이동)"
            setTextColor(Color.WHITE); isChecked = config.mouseAcceleration; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.mouseAcceleration = checked }
        })
        root.addView(Switch(this).apply {
            text = "GPU 하드웨어 가속 추론 (재시작 시 적용)"
            setTextColor(Color.WHITE); isChecked = config.useGpuDelegate; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.useGpuDelegate = checked }
        })
        root.addView(Switch(this).apply {
            text = "머물러 자동 클릭 (Dwell Click: 커서 1초 고정 시 자동 클릭)"
            setTextColor(Color.WHITE); isChecked = config.dwellClickEnabled; setPadding(0, dp(6), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.dwellClickEnabled = checked }
        })

        val speedLabel = TextView(this).apply { text = "마우스 포인터 이동 속도(DPI 배율): ${String.format("%.2f", config.cursorSpeed)}x"; setTextColor(Color.LTGRAY); textSize = 12.5f }
        root.addView(speedLabel)
        root.addView(SeekBar(this).apply {
            max = 260; progress = ((config.cursorSpeed - 0.6f) * 100f).toInt().coerceIn(0, 260)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { val v = 0.6f + (p / 100f); config.cursorSpeed = v; speedLabel.text = "마우스 포인터 이동 속도(DPI 배율): ${String.format("%.2f", v)}x" }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val pinchLabel = TextView(this).apply { text = "\uD83D\uDC4C 핀치 클릭 민감도 (엄지↔검지 끝 비율): ${String.format("%.2f", config.pinchRatioThreshold)}"; setTextColor(Color.LTGRAY); textSize = 12.5f; setPadding(0, dp(10), 0, 0) }
        root.addView(pinchLabel)
        root.addView(SeekBar(this).apply {
            max = 30; progress = ((config.pinchRatioThreshold - 0.18f) * 100f).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { val v = 0.18f + (p / 100f); config.pinchRatioThreshold = v; pinchLabel.text = "\uD83D\uDC4C 핀치 클릭 민감도 (엄지↔검지 끝 비율): ${String.format("%.2f", v)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(createHeader("8 HAND POSES (손을 완전히 멈추고 0.6초 유지 시 실행 · 터치하여 변경)"))
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
        scrollTestCounterText.text = "\uD83D\uDD90\uFE0F [손바닥 스크롤 실시간 검증] 손바닥을 펴고 위·아래·좌·우로 가볍게(약 4cm) 쓸어보세요!\n" +
            "  • ▼ 아래 스크롤: ${AirTouchTelemetry.scrollDownCount}회  |  ▲ 위로 스크롤: ${AirTouchTelemetry.scrollUpCount}회  |  ◀▶ 좌·우: ${AirTouchTelemetry.swipeSideCount}회"
        if (!config.showLiveMonitorCard) return
        if (!LauncherAirTouchBridge.isRunning()) { liveTelemetryText.text = "• 상태: 추적 대기 중 (아래 '3. AirTouch 제스처 추적 시작' 버튼을 눌러주세요)"; return }
        liveTelemetryText.text = "• 손 감지 여부 : ${if (AirTouchTelemetry.handDetected) "● 21개 관절 인식됨 (지연: ${AirTouchTelemetry.inferenceLatencyMs}ms)" else "○ 카메라 시야에 손 없음"}\n" +
            "• 분석 해상도  : ${AirTouchTelemetry.cameraResolutionText} (스트라이드 패딩 무손실 보정)\n" +
            "• 카메라~손거리: 약 ${AirTouchTelemetry.estimatedDistanceCm} cm (변위 보정: ${String.format("%.2f", AirTouchTelemetry.distanceGainFactor)}x)\n" +
            "• 화면 방향    : ${AirTouchTelemetry.orientationText}\n" +
            "• 손가락 펴짐  : ${AirTouchTelemetry.fingerStatesText}\n• 현재 모드    : ${AirTouchTelemetry.currentModeText}\n" +
            "• 최근 실행    : ${AirTouchTelemetry.lastTriggeredActionText}\n" +
            "• 핀치 거리비율: ${String.format("%.2f", AirTouchTelemetry.pinchRatio)} (임계값 ${String.format("%.2f", config.pinchRatioThreshold)} 이하 시 클릭)"
    }

    private fun refreshDashboardState() {
        val running = LauncherAirTouchBridge.isRunning()
        statusText.text = "• 전면 카메라 권한 : ${if (hasCameraPermission()) "허용됨 (OK)" else "미허용 (권한 필요)"}\n" +
            "• 접근성 서비스    : ${if (LauncherAirTouchBridge.isAccessibilityEnabled(this)) "활성화됨 (OK)" else "비활성 (설정 필요)"}\n" +
            "• AirTouch 엔진    : ${if (running) "실행 중 (v2.7 다중프레임 손바닥 스크롤 엔진)" else "대기 중 (0MB 유휴 상태)"}"
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

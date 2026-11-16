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

/**
 * 단축 포즈에 매핑 가능한 시스템·미디어 액션 (기본 포인터/클릭/스크롤과 중복되는 항목 제거)
 */
enum class AirTouchAction(val titleKo: String) {
    NONE("동작 없음"),
    GLOBAL_BACK("뒤로 가기 (Back)"),
    GLOBAL_HOME("홈 화면 이동 (Home)"),
    GLOBAL_RECENTS("최근 앱 목록 (Recents)"),
    GLOBAL_NOTIFICATIONS("알림창 열기"),
    GLOBAL_QUICK_SETTINGS("빠른 설정창 열기"),
    GLOBAL_LOCK_SCREEN("화면 잠금 (Lock Screen)"),
    GLOBAL_SCREENSHOT("화면 캡처 (Screenshot)"),
    DOUBLE_TAP_AT_CURSOR("커서 위치 더블 클릭"),
    MEDIA_PLAY_PAUSE("미디어 재생 / 일시정지"),
    MEDIA_NEXT("다음 곡 / 영상"),
    MEDIA_PREV("이전 곡 / 영상"),
    VOLUME_UP("볼륨 높이기 (+)"),
    VOLUME_DOWN("볼륨 낮추기 (-)"),
    TOGGLE_PAUSE_TRACKING("커서 일시정지 / 재개 토글")
}

/**
 * 손바닥 스크롤(3~5손가락) 및 검지 포인팅(1손가락)과 절대 겹치지 않는 5가지 독립 단축 포즈
 */
enum class HandPose(val key: String, val labelKo: String, val howToKo: String, val defaultAction: AirTouchAction) {
    PEACE("pose_v30_peace", "\u270C\uFE0F 브이 (검지+중지)", "약지·새끼는 접고 검지·중지만 편 채 0.6초 정지", AirTouchAction.GLOBAL_BACK),
    ROCK_ON("pose_v30_rock", "\uD83E\uDD18 락앤롤 (검지+새끼)", "중지·약지는 접고 검지·새끼만 편 채 0.6초 정지", AirTouchAction.GLOBAL_HOME),
    SHAKA("pose_v30_shaka", "\uD83E\uDD19 샤카 (엄지+새끼)", "검지·중지·약지는 접고 엄지·새끼만 편 채 0.6초 정지", AirTouchAction.GLOBAL_RECENTS),
    THUMB_UP("pose_v30_thumb", "\uD83D\uDC4D 엄지 척 (엄지만 폄)", "네 손가락을 모두 접고 엄지만 편 채 0.6초 정지", AirTouchAction.MEDIA_PLAY_PAUSE),
    FIST("pose_v30_fist", "\u270A 주먹 쥐기 (모두 접음)", "다섯 손가락을 모두 말아 쥔 채 0.6초 정지", AirTouchAction.TOGGLE_PAUSE_TRACKING)
}

enum class ActiveGestureMode {
    PAUSED, PINCH_CLICK, PALM_SCROLL, SHORTCUT_POSE, POINTER_MOVE
}

class AirTouchConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("airtouch_lowram_prefs", Context.MODE_PRIVATE)

    var cursorSpeed: Float
        get() = prefs.getFloat("cursor_speed_v30", 1.75f)
        set(v) = prefs.edit().putFloat("cursor_speed_v30", v.coerceIn(0.6f, 3.2f)).apply()

    var pinchRatioThreshold: Float
        get() = prefs.getFloat("pinch_ratio_v30", 0.32f)
        set(v) = prefs.edit().putFloat("pinch_ratio_v30", v.coerceIn(0.18f, 0.46f)).apply()

    var swipeScrollEnabled: Boolean
        get() = prefs.getBoolean("swipe_scroll_v30", true)
        set(v) = prefs.edit().putBoolean("swipe_scroll_v30", v).apply()

    var palmSwipeThreshold: Float
        get() = prefs.getFloat("palm_swipe_thresh_v30", 0.075f)
        set(v) = prefs.edit().putFloat("palm_swipe_thresh_v30", v.coerceIn(0.045f, 0.150f)).apply()

    var invertPalmScroll: Boolean
        get() = prefs.getBoolean("invert_palm_scroll_v30", false)
        set(v) = prefs.edit().putBoolean("invert_palm_scroll_v30", v).apply()

    var showOverlayStatusPill: Boolean
        get() = prefs.getBoolean("show_overlay_status_pill", true)
        set(v) = prefs.edit().putBoolean("show_overlay_status_pill", v).apply()

    var showSkeletonMiniView: Boolean
        get() = prefs.getBoolean("show_skeleton_mini_v26", true)
        set(v) = prefs.edit().putBoolean("show_skeleton_mini_v26", v).apply()

    var showLiveMonitorCard: Boolean
        get() = prefs.getBoolean("show_live_monitor_card", true)
        set(v) = prefs.edit().putBoolean("show_live_monitor_card", v).apply()

    fun getActionForPose(pose: HandPose): AirTouchAction =
        runCatching { AirTouchAction.valueOf(prefs.getString(pose.key, pose.defaultAction.name)!!) }.getOrDefault(pose.defaultAction)

    fun setActionForPose(pose: HandPose, action: AirTouchAction) =
        prefs.edit().putString(pose.key, action.name).apply()

    fun resetDefaults() {
        val ed = prefs.edit()
            .putFloat("cursor_speed_v30", 1.75f)
            .putFloat("pinch_ratio_v30", 0.32f)
            .putBoolean("swipe_scroll_v30", true)
            .putFloat("palm_swipe_thresh_v30", 0.075f)
            .putBoolean("invert_palm_scroll_v30", false)
        for (p in HandPose.values()) ed.putString(p.key, p.defaultAction.name)
        ed.apply()
    }
}

object AirTouchTelemetry {
    @Volatile var handDetected: Boolean = false
    @Volatile var fingerStatesText: String = "손 대기 중..."
    @Volatile var currentModeText: String = "카메라 앞에 손을 보여주세요"
    @Volatile var pinchRatio: Float = 1.0f
    @Volatile var estimatedDistanceCm: Int = 35
    @Volatile var orientationText: String = "세로 모드"
    @Volatile var cameraResolutionText: String = "대기 중"
    @Volatile var lastTriggeredActionText: String = "없음"
    @Volatile var inferenceLatencyMs: Long = 0L
    @Volatile var scrollCount: Int = 0
}

/**
 * 수학적으로 0 < alpha < 1 범위가 항상 보장되는 정통 1€ 필터
 */
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
        val aPos = smoothingFactor(dt, minCutoffHz + beta * hypot(dxHat, dyHat)).coerceIn(0.05f, 0.90f)
        xHat += aPos * (clampedX - xHat)
        yHat += aPos * (clampedY - yHat)
        return Pair(xHat, yHat)
    }

    private fun smoothingFactor(dt: Float, cutoffHz: Float): Float {
        val r = 2.0f * PI.toFloat() * cutoffHz * dt
        return r / (r + 1.0f)
    }
}

class GestureClassifier(private val config: AirTouchConfig) {
    enum class ScrollDirection { NONE, DOWN, UP, LEFT, RIGHT }

    data class FrameEvaluation(
        val mode: ActiveGestureMode,
        val normCursorX: Float,
        val normCursorY: Float,
        val isPinching: Boolean,
        val gaugeProgress: Float,
        val tapClicked: Boolean,
        val longPressed: Boolean,
        val triggeredPoseAction: AirTouchAction?,
        val scrollDirection: ScrollDirection,
        val scrollStrength: Float,
        val activeScrollBadge: String,
        val nearFrameEdge: Boolean,
        val estimatedCm: Int,
        val landmarksXY: FloatArray,
        val modeSummary: String
    )

    private val fingerFilter = StableOneEuroFilter(minCutoffHz = 1.50f, beta = 16.5f)
    private val knuckleFilter = StableOneEuroFilter(minCutoffHz = 1.20f, beta = 12.0f)
    private val palmFilter = StableOneEuroFilter(minCutoffHz = 2.20f, beta = 20.0f)

    @Volatile private var cursorX = 0.5f
    @Volatile private var cursorY = 0.5f
    private var prevFingerX = 0.5f; private var prevFingerY = 0.5f
    private var prevKnuckleX = 0.5f; private var prevKnuckleY = 0.5f
    private var lastValidEvalMs = 0L
    private var handEnteredFrameMs = 0L
    private var smoothedHandScale2D = 0.18f; private var handScaleInitialized = false

    private val fingerScore = FloatArray(5) { 0f }
    private val fingerState = BooleanArray(5) { false }

    private var prevPinchRatio = 1.0f
    private var isCurrentlyPinching = false
    private var pinchStartTimeMs = 0L
    private var longPressTriggered = false
    private var postPinchFreezeUntilMs = 0L

    private var palmModeLatchUntilMs = 0L
    private val bufSize = 12
    private val palmBufX = FloatArray(bufSize)
    private val palmBufY = FloatArray(bufSize)
    private val palmBufTs = LongArray(bufSize)
    private var palmBufHead = 0
    private var palmBufCount = 0
    private var prevSmoothPalmX = -1f; private var prevSmoothPalmY = -1f
    private var lastSwipeTriggerTimeMs = 0L
    private var lastScrollDir = ScrollDirection.NONE
    private var scrollBadgeUntilMs = 0L
    private var scrollBadgeLabel = ""

    private var currentCandidatePose: HandPose? = null
    private var poseStartTimeMs = 0L
    private var poseTriggeredInHold = false

    // 스레드 경합(Race Condition) 완전 방지용 4슬롯 랜드마크 링버퍼
    private val landmarkRing = Array(4) { FloatArray(42) }
    private var landmarkRingIdx = 0

    @Volatile var isTrackingPaused: Boolean = false; private set

    fun recenterCursor() { cursorX = 0.5f; cursorY = 0.5f }

    fun evaluate(lm: List<NormalizedLandmark>): FrameEvaluation {
        val now = SystemClock.uptimeMillis()
        val outLm = landmarkRing[landmarkRingIdx]
        landmarkRingIdx = (landmarkRingIdx + 1) % landmarkRing.size
        for (i in 0 until minOf(21, lm.size)) {
            outLm[i * 2] = lm[i].x()
            outLm[i * 2 + 1] = lm[i].y()
        }

        val wrist = lm[0]; val thumbTip = lm[4]; val indexMcp = lm[5]; val indexPip = lm[6]
        val indexTip = lm[8]; val middleMcp = lm[9]; val pinkyMcp = lm[17]

        val rawScale2D = maxOf(dist2D(wrist, middleMcp), dist2D(wrist, indexMcp) * 0.95f, dist2D(indexMcp, pinkyMcp) * 1.18f).coerceIn(0.06f, 0.55f)
        if (!handScaleInitialized || (now - lastValidEvalMs) > 600L) {
            smoothedHandScale2D = rawScale2D; handScaleInitialized = true
        } else {
            smoothedHandScale2D += (rawScale2D - smoothedHandScale2D) * 0.10f
        }
        val handScale = smoothedHandScale2D
        val estCm = (6.4f / handScale).roundToInt().coerceIn(15, 95)
        AirTouchTelemetry.estimatedDistanceCm = estCm
        val distGain = (0.185f / handScale).coerceIn(0.65f, 1.95f)

        val nearEdge = indexMcp.x() !in 0.03f..0.97f || indexMcp.y() !in 0.03f..0.97f || indexTip.x() !in 0.01f..0.99f || indexTip.y() !in 0.01f..0.99f
        val (smoothFingerX, smoothFingerY) = fingerFilter.filter(
            indexTip.x() * 0.45f + indexPip.x() * 0.35f + indexMcp.x() * 0.20f,
            indexTip.y() * 0.45f + indexPip.y() * 0.35f + indexMcp.y() * 0.20f,
            now
        )
        val (smoothKnuckleX, smoothKnuckleY) = knuckleFilter.filter(indexMcp.x(), indexMcp.y(), now)
        val (palmX, palmY) = palmFilter.filter(
            wrist.x() * 0.30f + indexMcp.x() * 0.25f + middleMcp.x() * 0.25f + pinkyMcp.x() * 0.20f,
            wrist.y() * 0.30f + indexMcp.y() * 0.25f + middleMcp.y() * 0.25f + pinkyMcp.y() * 0.20f,
            now
        )

        val handReentered = (now - lastValidEvalMs) > 220L
        if (handReentered) {
            handEnteredFrameMs = now
            prevFingerX = smoothFingerX; prevFingerY = smoothFingerY
            prevKnuckleX = smoothKnuckleX; prevKnuckleY = smoothKnuckleY
            prevSmoothPalmX = palmX; prevSmoothPalmY = palmY
            prevPinchRatio = dist2D(thumbTip, indexTip) / handScale
            palmBufCount = 0; palmBufHead = 0; palmModeLatchUntilMs = 0L
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
            (pinchRatio < ratioThreshold * 1.15f) ||
            (pinchClosingRate > 0.015f && pinchRatio < ratioThreshold * 1.38f)

        // [모드 분리 핵심] 검지+중지+약지 3개 이상 펴고 엄지-검지가 벌어진 상태 = 100% 손바닥 스크롤 전용 모드!
        val isOpenPalmNow = config.swipeScrollEnabled && indexUp && middleUp && ringUp && !isCurrentlyPinching && !pinchActive && (pinchRatio > ratioThreshold * 1.60f)
        if (isOpenPalmNow) {
            palmModeLatchUntilMs = now + 240L
        } else if (!indexUp || isCurrentlyPinching || pinchActive || (!middleUp && !ringUp)) {
            // 검지 포인팅(☝️)이나 클릭(OK), 주먹(✊)으로 바꾸면 즉시 손바닥 모드 해제
            palmModeLatchUntilMs = 0L
        }
        val inPalmScrollMode = !isTrackingPaused && (now < palmModeLatchUntilMs)

        val instStepX = if (prevSmoothPalmX >= 0f) (palmX - prevSmoothPalmX) else 0f
        val instStepY = if (prevSmoothPalmY >= 0f) (palmY - prevSmoothPalmY) else 0f
        val palmSpeedNorm = (hypot(instStepX, instStepY) / dtSec) * distGain
        prevSmoothPalmX = palmX; prevSmoothPalmY = palmY

        // 1) 클릭 & 길게 누르기 판정 (일시정지 및 손바닥 스크롤 모드와 완전 분리)
        var tapClicked = false; var longPressed = false; var pinchProgress = 0f
        if (!isTrackingPaused && pinchActive) {
            if (!isCurrentlyPinching) {
                isCurrentlyPinching = true; pinchStartTimeMs = now; longPressTriggered = false
            } else {
                val heldMs = now - pinchStartTimeMs
                pinchProgress = (heldMs.toFloat() / 650f).coerceIn(0f, 1f)
                if (!longPressTriggered && heldMs >= 650L) {
                    longPressed = true; longPressTriggered = true; postPinchFreezeUntilMs = now + 220L
                    AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 길게 누르기 (Long Press)"
                }
            }
        } else if (isCurrentlyPinching) {
            val heldMs = now - pinchStartTimeMs
            if (!longPressTriggered && heldMs in 18L..649L && !isTrackingPaused) {
                tapClicked = true; postPinchFreezeUntilMs = now + 220L
                AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 단일 클릭 (Tap)"
            }
            isCurrentlyPinching = false
        }

        // 2) 손바닥 스크롤 슬라이딩 윈도우 판정 (커서는 제자리에 고정된 상태로 상하좌우 스크롤만 깔끔하게 실행)
        var scrollDir = ScrollDirection.NONE
        var scrollStrength = 1.0f
        if (inPalmScrollMode) {
            palmBufX[palmBufHead] = palmX; palmBufY[palmBufHead] = palmY; palmBufTs[palmBufHead] = now
            palmBufHead = (palmBufHead + 1) % bufSize
            if (palmBufCount < bufSize) palmBufCount++

            if ((now - handEnteredFrameMs) > 150L && (now - lastSwipeTriggerTimeMs) > 280L && palmBufCount >= 2) {
                var bestDx = 0f; var bestDy = 0f; var bestMag = 0f; var bestDtMs = 100L
                for (k in 1 until palmBufCount) {
                    val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
                    val dtWin = now - palmBufTs[idx]
                    if (dtWin < 40L) continue
                    if (dtWin > 290L) break
                    val dx = (palmX - palmBufX[idx]) * distGain
                    val dy = (palmY - palmBufY[idx]) * distGain
                    val mag = hypot(dx, dy)
                    if (mag > bestMag) { bestMag = mag; bestDx = dx; bestDy = dy; bestDtMs = dtWin }
                }
                val baseThresh = config.palmSwipeThreshold
                val avgSpeed = bestMag / (bestDtMs.coerceAtLeast(40L) / 1000f)
                if (bestMag >= baseThresh && avgSpeed >= 0.32f) {
                    var cand = ScrollDirection.NONE
                    var domDisp = 0f
                    if (abs(bestDy) >= baseThresh && abs(bestDy) > abs(bestDx) * 1.15f && (bestDy * instStepY >= -0.002f)) {
                        val moveUp = bestDy < 0f
                        cand = if (!config.invertPalmScroll) (if (moveUp) ScrollDirection.DOWN else ScrollDirection.UP)
                        else (if (moveUp) ScrollDirection.UP else ScrollDirection.DOWN)
                        domDisp = abs(bestDy)
                    } else if (abs(bestDx) >= baseThresh * 1.08f && abs(bestDx) > abs(bestDy) * 1.20f && (bestDx * instStepX >= -0.002f)) {
                        cand = if (bestDx < 0f) ScrollDirection.LEFT else ScrollDirection.RIGHT
                        domDisp = abs(bestDx)
                    }
                    if (cand != ScrollDirection.NONE) {
                        val sinceLast = now - lastSwipeTriggerTimeMs
                        val isOpposite = (lastScrollDir == ScrollDirection.DOWN && cand == ScrollDirection.UP) ||
                            (lastScrollDir == ScrollDirection.UP && cand == ScrollDirection.DOWN) ||
                            (lastScrollDir == ScrollDirection.LEFT && cand == ScrollDirection.RIGHT) ||
                            (lastScrollDir == ScrollDirection.RIGHT && cand == ScrollDirection.LEFT)
                        if (!isOpposite || sinceLast > 820L || (domDisp >= baseThresh * 1.65f && avgSpeed >= 0.70f)) {
                            scrollDir = cand
                            scrollStrength = (domDisp / baseThresh).coerceIn(1.0f, 1.85f)
                            lastSwipeTriggerTimeMs = now
                            lastScrollDir = cand
                            palmBufCount = 0; palmBufHead = 0
                            AirTouchTelemetry.scrollCount++
                            scrollBadgeLabel = when (cand) {
                                ScrollDirection.DOWN -> "▼ 아래 스크롤"
                                ScrollDirection.UP -> "▲ 위로 스크롤"
                                ScrollDirection.LEFT -> "◀ 왼쪽 스와이프"
                                ScrollDirection.RIGHT -> "▶ 오른쪽 스와이프"
                                else -> ""
                            }
                            scrollBadgeUntilMs = now + 520L
                            AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDD90\uFE0F $scrollBadgeLabel"
                        }
                    }
                }
            }
        } else {
            palmBufCount = 0; palmBufHead = 0
        }

        // 3) 5종 독립 정적 단축 포즈 판정 (스크롤 모드·포인팅·핀치와 완전히 독립된 모양만 매칭)
        val rawPose = if (inPalmScrollMode || isCurrentlyPinching || pinchFreeze || (now - lastSwipeTriggerTimeMs) < 900L) {
            null
        } else when {
            !thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.FIST
            isTrackingPaused -> null // 일시정지 중에는 오직 주먹(FIST) 해제만 허용!
            indexUp && middleUp && !ringUp && !pinkyUp -> HandPose.PEACE
            indexUp && !middleUp && !ringUp && pinkyUp && !thumbUp -> HandPose.ROCK_ON
            thumbUp && !indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.SHAKA
            thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.THUMB_UP
            else -> null
        }

        var poseProgress = 0f
        var triggeredPoseAction: AirTouchAction? = null
        if (rawPose != null && palmSpeedNorm < 0.13f) {
            if (rawPose != currentCandidatePose) {
                currentCandidatePose = rawPose; poseStartTimeMs = now; poseTriggeredInHold = false
            } else {
                val elapsed = now - poseStartTimeMs
                poseProgress = (elapsed.toFloat() / 600f).coerceIn(0f, 1f)
                if (!poseTriggeredInHold && elapsed >= 600L) {
                    poseTriggeredInHold = true
                    val mapped = config.getActionForPose(rawPose)
                    if (mapped == AirTouchAction.TOGGLE_PAUSE_TRACKING) {
                        isTrackingPaused = !isTrackingPaused
                        triggeredPoseAction = mapped
                        AirTouchTelemetry.lastTriggeredActionText = if (isTrackingPaused) "일시정지 켜짐" else "일시정지 해제됨"
                    } else if (!isTrackingPaused) {
                        triggeredPoseAction = mapped
                        AirTouchTelemetry.lastTriggeredActionText = "${rawPose.labelKo} -> ${mapped.titleKo}"
                    }
                }
            }
        } else if (palmSpeedNorm >= 0.16f || rawPose == null) {
            currentCandidatePose = null; poseTriggeredInHold = false
        }

        // 4) 마우스 포인터 이동 (오직 포인터 조준 모드에서만 동작! 손바닥 스크롤·클릭·포즈 충전 중에는 0.0px 완전 고정!)
        val freezeForPoseHold = poseProgress > 0.18f
        if (!isTrackingPaused && !inPalmScrollMode && !handReentered && !pinchFreeze && !freezeForPoseHold) {
            val dFingerX = smoothFingerX - prevFingerX; val dFingerY = smoothFingerY - prevFingerY
            val dKnuckleX = smoothKnuckleX - prevKnuckleX; val dKnuckleY = smoothKnuckleY - prevKnuckleY
            val nearPinchBlend = if (pinchRatio < ratioThreshold * 1.38f) {
                ((pinchRatio - ratioThreshold * 1.15f) / (ratioThreshold * 0.23f)).coerceIn(0.18f, 1.0f)
            } else 1.0f
            val rawDx = dFingerX * nearPinchBlend + dKnuckleX * (1f - nearPinchBlend) * 0.5f
            val rawDy = dFingerY * nearPinchBlend + dKnuckleY * (1f - nearPinchBlend) * 0.5f
            val mag = hypot(rawDx, rawDy)
            val softKnee = mag / (mag + 0.00070f)
            val normVel = (mag / dtSec) * distGain
            val accel = if (nearPinchBlend > 0.7f) 1.0f + (normVel * 0.55f).coerceAtMost(1.35f) else 1.0f
            val edgeFactor = if (nearEdge) 0.35f else 1.0f
            val gain = config.cursorSpeed * 1.90f * distGain * softKnee * accel * nearPinchBlend * edgeFactor
            cursorX = (cursorX + rawDx * gain).coerceIn(0f, 1f)
            cursorY = (cursorY + rawDy * gain * 1.08f).coerceIn(0f, 1f)
        }
        prevFingerX = smoothFingerX; prevFingerY = smoothFingerY
        prevKnuckleX = smoothKnuckleX; prevKnuckleY = smoothKnuckleY

        val mode = when {
            isTrackingPaused -> ActiveGestureMode.PAUSED
            isCurrentlyPinching -> ActiveGestureMode.PINCH_CLICK
            inPalmScrollMode -> ActiveGestureMode.PALM_SCROLL
            poseProgress > 0.15f && currentCandidatePose != null -> ActiveGestureMode.SHORTCUT_POSE
            else -> ActiveGestureMode.POINTER_MOVE
        }

        val activeBadge = if (now < scrollBadgeUntilMs) scrollBadgeLabel else ""
        val summary = when (mode) {
            ActiveGestureMode.PAUSED -> "[일시정지] \u270A 주먹 0.6초 쥐어 재개"
            ActiveGestureMode.PINCH_CLICK -> "\uD83D\uDC4C 클릭 고정됨 (${estCm}cm · 떼면 클릭 / 유지 시 길게 누르기)"
            ActiveGestureMode.PALM_SCROLL -> if (activeBadge.isNotEmpty()) "\uD83D\uDD90\uFE0F $activeBadge" else "\uD83D\uDD90\uFE0F 손바닥 스크롤 모드 (커서 고정 · 상하좌우 쓸기)"
            ActiveGestureMode.SHORTCUT_POSE -> "${currentCandidatePose!!.labelKo} (${(poseProgress * 100).toInt()}%)"
            ActiveGestureMode.POINTER_MOVE -> if (nearEdge) "손을 카메라 안쪽으로 옮겨주세요 (${estCm}cm)" else "\u261D\uFE0F 포인터 이동 모드 (${estCm}cm · \uD83D\uDC4C클릭 / \uD83D\uDD90\uFE0F스크롤)"
        }
        AirTouchTelemetry.currentModeText = summary

        return FrameEvaluation(
            mode = mode,
            normCursorX = cursorX,
            normCursorY = cursorY,
            isPinching = isCurrentlyPinching,
            gaugeProgress = maxOf(poseProgress, pinchProgress),
            tapClicked = tapClicked,
            longPressed = longPressed,
            triggeredPoseAction = triggeredPoseAction,
            scrollDirection = scrollDir,
            scrollStrength = scrollStrength,
            activeScrollBadge = activeBadge,
            nearFrameEdge = nearEdge,
            estimatedCm = estCm,
            landmarksXY = outLm,
            modeSummary = summary
        )
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

/**
 * 4대 모드별 색상 분리 오버레이 커서 (초록=포인터, 노랑=클릭, 하늘=손바닥 스크롤, 빨강=일시정지)
 */
class CursorOverlayView(context: Context) : View(context) {
    companion object {
        private val HAND_BONES = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8,
            5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16,
            0, 17, 13, 17, 17, 18, 18, 19, 19, 20
        )
    }
    private val density = context.resources.displayMetrics.density
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6.5f; color = Color.parseColor("#00E676") }
    private val cursorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9.5f; color = Color.parseColor("#66000000") }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00B0FF") }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#CC101418") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13.5f * density; typeface = Typeface.DEFAULT_BOLD }
    private val cursorBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); textSize = 12.2f * density; typeface = Typeface.DEFAULT_BOLD }
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
    var currentMode = ActiveGestureMode.POINTER_MOVE
    var handVisible = false; var nearEdge = false; var estimatedCm = 35
    var gaugeProgress = 0f; var statusLabel = "AirTouch 대기 중"
    var scrollFeedbackBadge = ""
    var showStatusPill = true; var showSkeletonMini = true

    fun updateState(
        x: Float, y: Float, mode: ActiveGestureMode, handFound: Boolean, edgeWarn: Boolean, estCm: Int,
        landmarks: FloatArray?, progress: Float, label: String, scrollBadge: String, showPill: Boolean, showMini: Boolean
    ) {
        targetX = x; targetY = y; currentMode = mode; handVisible = handFound; nearEdge = edgeWarn; estimatedCm = estCm
        if (landmarks != null && landmarks.size >= 42) System.arraycopy(landmarks, 0, lmPts, 0, 42)
        gaugeProgress = progress; statusLabel = label; scrollFeedbackBadge = scrollBadge
        showStatusPill = showPill; showSkeletonMini = showMini
        if (mode == ActiveGestureMode.PINCH_CLICK || hypot(targetX - renderX, targetY - renderY) > 360f) {
            renderX = targetX; renderY = targetY
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastDrawMs).coerceIn(4L, 40L)) / 1000f
        lastDrawMs = now
        val dx = targetX - renderX; val dy = targetY - renderY
        if (currentMode == ActiveGestureMode.PINCH_CLICK || hypot(dx, dy) < 0.35f) {
            renderX = targetX; renderY = targetY
        } else {
            val blend = (1.0f - exp(-36.0f * dt)).coerceIn(0.18f, 0.85f)
            renderX += dx * blend; renderY += dy * blend
            postInvalidateOnAnimation()
        }

        val isPinch = currentMode == ActiveGestureMode.PINCH_CLICK
        val isPalm = currentMode == ActiveGestureMode.PALM_SCROLL
        val r = if (isPinch) 20f else 28f
        val themeColor = when (currentMode) {
            ActiveGestureMode.PAUSED -> Color.parseColor("#FF5252")
            ActiveGestureMode.PINCH_CLICK -> Color.parseColor("#FFD740")
            ActiveGestureMode.PALM_SCROLL -> Color.parseColor("#00E5FF")
            ActiveGestureMode.SHORTCUT_POSE -> Color.parseColor("#40C4FF")
            ActiveGestureMode.POINTER_MOVE -> Color.parseColor("#00E676")
        }
        outerRingPaint.color = themeColor
        canvas.drawCircle(renderX, renderY, r, cursorShadowPaint)
        canvas.drawCircle(renderX, renderY, r, outerRingPaint)

        // 스크롤 모드에서는 커서 중앙에 십자 가이드(+)를 그려 포인터 이동과 명확히 구분
        if (isPalm) {
            val arm = 9f * density
            canvas.drawLine(renderX - arm, renderY, renderX + arm, renderY, outerRingPaint)
            canvas.drawLine(renderX, renderY - arm, renderX, renderY + arm, outerRingPaint)
        } else {
            canvas.drawCircle(renderX, renderY, if (isPinch) 10.5f else 5.5f, centerDotPaint)
        }

        if (gaugeProgress > 0.04f) {
            arcRect.set(renderX - r - 11f, renderY - r - 11f, renderX + r + 11f, renderY + r + 11f)
            canvas.drawArc(arcRect, -90f, 360f * gaugeProgress, false, progressArcPaint)
        }

        val tagText = if (scrollFeedbackBadge.isNotEmpty()) scrollFeedbackBadge else if (isPalm) "스크롤 모드" else ""
        if (tagText.isNotEmpty()) {
            val tw = cursorBadgePaint.measureText(tagText)
            val bx = (renderX - tw * 0.5f - 10f * density).coerceIn(8f, (width - tw - 28f * density).coerceAtLeast(8f))
            val by = (renderY - r - 34f * density).coerceAtLeast(12f * density)
            scrollTagRect.set(bx, by, bx + tw + 20f * density, by + 24f * density)
            canvas.drawRoundRect(scrollTagRect, 12f * density, 12f * density, pillBgPaint)
            canvas.drawText(tagText, bx + 10f * density, by + 17f * density, cursorBadgePaint)
        }

        if (showStatusPill && statusLabel.isNotEmpty()) {
            val tw = labelPaint.measureText(statusLabel); val pw = tw + 36f * density
            val pl = ((width - pw) * 0.5f).coerceAtLeast(12f * density)
            val topY = 38f * density
            pillRect.set(pl, topY, pl + pw, topY + 30f * density)
            canvas.drawRoundRect(pillRect, 16f * density, 16f * density, pillBgPaint)
            canvas.drawText(statusLabel, pl + 18f * density, topY + 20f * density, labelPaint)
        }

        if (showSkeletonMini) drawHandSkeletonMiniHud(canvas, themeColor)
    }

    private fun drawHandSkeletonMiniHud(canvas: Canvas, activeColor: Int) {
        val boxW = 108f * density; val boxH = 138f * density
        val left = (width - boxW - 14f * density).coerceAtLeast(8f)
        val top = (height - boxH - 84f * density).coerceAtLeast(8f)
        miniRect.set(left, top, left + boxW, top + boxH)
        miniBoxBorderPaint.color = if (!handVisible) Color.parseColor("#546E7A") else if (nearEdge) Color.parseColor("#FFA726") else activeColor
        canvas.drawRoundRect(miniRect, 12f * density, 12f * density, miniBoxBgPaint)
        canvas.drawRoundRect(miniRect, 12f * density, 12f * density, miniBoxBorderPaint)
        if (!handVisible) {
            val msg = if (currentMode == ActiveGestureMode.PAUSED) "일시정지됨" else "손 미감지"
            val mw = miniTextPaint.measureText(msg)
            canvas.drawText(msg, left + (boxW - mw) * 0.5f, top + boxH * 0.52f, miniTextPaint)
            return
        }
        val pad = 10f * density; val drawW = boxW - pad * 2f; val drawH = boxH - pad * 2f - 14f * density
        bonePaint.color = if (nearEdge) Color.parseColor("#FFA726") else activeColor
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
                keyJointPaint.color = if (currentMode == ActiveGestureMode.PINCH_CLICK) Color.parseColor("#FFD740") else Color.parseColor("#00B0FF")
                canvas.drawCircle(jx, jy, 3.8f * density, keyJointPaint)
            } else canvas.drawCircle(jx, jy, 2.0f * density, jointPaint)
        }
        val modeTag = when (currentMode) {
            ActiveGestureMode.PALM_SCROLL -> "스크롤 · ${estimatedCm}cm"
            ActiveGestureMode.PINCH_CLICK -> "클릭 · ${estimatedCm}cm"
            ActiveGestureMode.PAUSED -> "일시정지"
            else -> "포인터 · ${estimatedCm}cm"
        }
        canvas.drawText(modeTag, left + 8f * density, top + boxH - 6f * density, miniTextPaint)
    }
}

class AirTouchAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: AirTouchAccessibilityService? = null; private set }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: CursorOverlayView? = null
    private lateinit var config: AirTouchConfig
    private var screenWidth = 1080; private var screenHeight = 2400
    @Volatile private var gestureBusyUntilMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this; config = AirTouchConfig(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
        if (AirTouchTrackingService.isRunning) showCursorOverlay()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { gestureBusyUntilMs = 0L }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); updateScreenMetrics() }
    override fun onDestroy() { hideCursorOverlay(); if (instance === this) instance = null; super.onDestroy() }

    private fun updateScreenMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds; screenWidth = b.width().coerceAtLeast(200); screenHeight = b.height().coerceAtLeast(200)
        } else {
            val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels.coerceAtLeast(200); screenHeight = dm.heightPixels.coerceAtLeast(200)
        }
        AirTouchTelemetry.orientationText = if (screenWidth > screenHeight) "가로 (${screenWidth}x${screenHeight})" else "세로 (${screenWidth}x${screenHeight})"
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

    // 수정 완료: 일시정지 상태에서 손을 내렸을 때 일시정지 색상(빨강)과 문구가 풀리지 않도록 보존
    fun notifyNoHandDetected(isPaused: Boolean) = mainHandler.post {
        val mode = if (isPaused) ActiveGestureMode.PAUSED else ActiveGestureMode.POINTER_MOVE
        val msg = if (isPaused) "[일시정지] \u270A 주먹 0.6초 쥐어 재개" else "전면 카메라 시야에 손을 보여주세요"
        cursorView?.let {
            it.updateState(it.targetX, it.targetY, mode, false, false, AirTouchTelemetry.estimatedDistanceCm, null, 0f, msg, "", config.showOverlayStatusPill, config.showSkeletonMiniView)
        }
    }

    fun updateCursorFromEvaluation(eval: GestureClassifier.FrameEvaluation) = mainHandler.post {
        if (cursorView == null) showCursorOverlay()
        val px = (eval.normCursorX * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val py = (eval.normCursorY * screenHeight).coerceIn(0f, screenHeight.toFloat())
        cursorView?.updateState(
            px, py, eval.mode, true, eval.nearFrameEdge, eval.estimatedCm,
            eval.landmarksXY, eval.gaugeProgress, eval.modeSummary, eval.activeScrollBadge,
            config.showOverlayStatusPill, config.showSkeletonMiniView
        )
        when {
            eval.tapClicked -> dispatchTap(px, py, 45L)
            eval.longPressed -> dispatchTap(px, py, 580L)
            eval.scrollDirection != GestureClassifier.ScrollDirection.NONE -> performScroll(eval.scrollDirection, px, py, eval.scrollStrength)
            eval.triggeredPoseAction != null && eval.triggeredPoseAction != AirTouchAction.TOGGLE_PAUSE_TRACKING -> performShortcutAction(eval.triggeredPoseAction, px, py)
        }
    }

    private fun performScroll(dir: GestureClassifier.ScrollDirection, x: Float, y: Float, strength: Float) {
        val safeX = x.coerceIn(screenWidth * 0.22f, screenWidth * 0.78f)
        val safeY = y.coerceIn(screenHeight * 0.25f, screenHeight * 0.75f)
        val halfSpanY = (0.19f + (strength - 1.0f) * 0.09f).coerceIn(0.19f, 0.28f)
        val dur = (220L - ((strength - 1.0f) * 50f).toLong()).coerceIn(165L, 230L)
        when (dir) {
            GestureClassifier.ScrollDirection.DOWN -> dispatchSwipe(safeX, screenHeight * (0.50f + halfSpanY), safeX, screenHeight * (0.50f - halfSpanY), dur)
            GestureClassifier.ScrollDirection.UP -> dispatchSwipe(safeX, screenHeight * (0.50f - halfSpanY), safeX, screenHeight * (0.50f + halfSpanY), dur)
            GestureClassifier.ScrollDirection.LEFT -> dispatchSwipe(screenWidth * 0.76f, safeY, screenWidth * 0.24f, safeY, 195L)
            GestureClassifier.ScrollDirection.RIGHT -> dispatchSwipe(screenWidth * 0.24f, safeY, screenWidth * 0.76f, safeY, 195L)
            GestureClassifier.ScrollDirection.NONE -> Unit
        }
    }

    private fun performShortcutAction(action: AirTouchAction, x: Float, y: Float) {
        when (action) {
            AirTouchAction.NONE, AirTouchAction.TOGGLE_PAUSE_TRACKING -> Unit
            AirTouchAction.DOUBLE_TAP_AT_CURSOR -> { dispatchTap(x, y, 40L); mainHandler.postDelayed({ dispatchTap(x, y, 40L) }, 95L) }
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

    // 수정 완료: 진행 중인 스크롤/롱프레스 제스처가 도중에 취소되지 않도록 완료 콜백 가드 적용
    private fun safeDispatchGesture(desc: GestureDescription, durationMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now < gestureBusyUntilMs) return
        gestureBusyUntilMs = now + durationMs + 25L
        val dispatched = dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { gestureBusyUntilMs = 0L }
            override fun onCancelled(gestureDescription: GestureDescription?) { gestureBusyUntilMs = 0L }
        }, mainHandler)
        if (!dispatched) gestureBusyUntilMs = 0L
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val maxX = (screenWidth - 2).coerceAtLeast(2).toFloat()
        val maxY = (screenHeight - 2).coerceAtLeast(2).toFloat()
        val p = Path().apply { moveTo(x.coerceIn(2f, maxX), y.coerceIn(2f, maxY)) }
        safeDispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), durationMs)
    }

    private fun dispatchSwipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        safeDispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), durationMs)
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
    private lateinit var config: AirTouchConfig
    private lateinit var classifier: GestureClassifier
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var handLandmarker: HandLandmarker? = null
    private val transformedRing = arrayOfNulls<Bitmap>(3)
    private val transformedCanvases = arrayOfNulls<Canvas>(3)
    private var ringIndex = 0
    private var fallbackRawBitmap: Bitmap? = null
    private val transformMatrix = Matrix()
    private val paintFilter = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cachedDisplayRotation = -1
    @Volatile private var cameraBound = false
    @Volatile private var lastFrameProcessTimeMs = 0L
    @Volatile private var lastHandDetectedTimeMs = 0L
    @Volatile private var lastSubmittedTimestampMs = 0L
    @Volatile private var inferenceBusy = false
    @Volatile private var lastInferenceStartMs = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    cameraBound = false
                    runCatching { cameraProvider?.unbindAll() }
                }
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (isRunning && !cameraBound && km?.isKeyguardLocked != true) {
                        cameraProvider?.let { bindAnalysisUseCase(it) }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (isRunning && !cameraBound) {
                        cameraProvider?.let { bindAnalysisUseCase(it) }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = AirTouchConfig(this)
        classifier = GestureClassifier(config)
        cameraExecutor = Executors.newSingleThreadExecutor()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rot = getCurrentDisplayRotation()
        if (rot != cachedDisplayRotation) {
            cachedDisplayRotation = rot
            runCatching { imageAnalysisUseCase?.targetRotation = rot }
        }
    }

    private fun getCurrentDisplayRotation(): Int = runCatching {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") wm.defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RECENTER) {
            classifier.recenterCursor()
            AirTouchAccessibilityService.instance?.centerCursorOverlay()
            return START_STICKY
        }
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTIFICATION_ID, notif)
        isRunning = true
        lastHandDetectedTimeMs = SystemClock.uptimeMillis()
        initHandLandmarker()
        if (!cameraBound) startCameraPipeline()
        AirTouchAccessibilityService.instance?.showCursorOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false; cameraBound = false
        AirTouchTelemetry.handDetected = false; AirTouchTelemetry.currentModeText = "추적 중지됨 (0MB 유휴 상태)"
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
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
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
        future.addListener({
            cameraProvider = future.get()
            if (isRunning && !cameraBound) cameraProvider?.let { bindAnalysisUseCase(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalysisUseCase(provider: ProcessCameraProvider) {
        val exec = cameraExecutor ?: return
        runCatching {
            provider.unbindAll()
            val resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(480, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
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
            cameraBound = true
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
        val targetFps = if ((now - lastHandDetectedTimeMs) > 4000L) 6 else 30
        if ((now - lastFrameProcessTimeMs) < (1000L / targetFps)) { imageProxy.close(); return }
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
            AirTouchTelemetry.handDetected = false
            AirTouchTelemetry.fingerStatesText = "손 미감지"
            AirTouchTelemetry.currentModeText = if (classifier.isTrackingPaused) "[일시정지 상태]" else "손 대기 중..."
            AirTouchAccessibilityService.instance?.notifyNoHandDetected(classifier.isTrackingPaused)
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
            .setContentTitle("AirTouch AI v3.0 실행 중")
            .setContentText("포인터(검지) · 클릭(엄지+검지) · 스크롤(손바닥) 분리 동작 중")
            .setContentIntent(openPending).addAction(android.R.drawable.ic_menu_close_clear_cancel, "즉시 끄기", stopPending).setOngoing(true).build()
    }
}

class AirTouchTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); syncTileState(AirTouchTrackingService.isRunning) }
    override fun onClick() {
        super.onClick()
        val nextState = LauncherAirTouchBridge.toggle(this)
        syncTileState(nextState)
    }
    private fun syncTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "AirTouch ON" else "AirTouch OFF"
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
    private lateinit var config: AirTouchConfig
    private lateinit var statusText: TextView
    private lateinit var monitorHeaderView: TextView
    private lateinit var liveTelemetryText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var practiceBtn: Button
    private lateinit var speedSeek: SeekBar
    private lateinit var pinchSeek: SeekBar
    private lateinit var scrollSeek: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var pinchLabel: TextView
    private lateinit var scrollSensLabel: TextView
    private lateinit var swipeSwitch: Switch
    private lateinit var invertSwitch: Switch
    private val poseUpdaters = mutableListOf<() -> Unit>()

    private var practiceClickCount = 0
    private var lastObservedRunning: Boolean? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            val runningNow = LauncherAirTouchBridge.isRunning()
            if (lastObservedRunning != runningNow) {
                lastObservedRunning = runningNow
                refreshDashboardState()
            } else {
                updateLiveTelemetryCard()
            }
            uiHandler.postDelayed(this, 160L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AirTouchConfig(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardState()
        uiHandler.post(uiRefreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiRefreshRunnable)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDashboardState()
        if (requestCode == 1002 && hasCameraPermission() && LauncherAirTouchBridge.isAccessibilityEnabled(this) && !LauncherAirTouchBridge.isRunning()) {
            LauncherAirTouchBridge.start(this)
            uiHandler.postDelayed({ refreshDashboardState() }, 150L)
        }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D0F12")) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(40)) }
        val pInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION") val vCode = pInfo?.versionCode ?: 0

        root.addView(TextView(this).apply {
            text = "AIRTOUCH AI v3.0 (Build $vCode)"
            textSize = 18.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676"))
        })
        root.addView(TextView(this).apply {
            text = "중복 기능 정리 완료 · 포인터(검지) / 클릭(핀치) / 스크롤(손바닥) 완전 분리 엔진"
            textSize = 12f; setTextColor(Color.parseColor("#9E9E9E")); setPadding(0, dp(4), 0, dp(12))
        })

        val hasLegacyConflictPkg = packageName != "com.prolauncher.airtouch" &&
            runCatching { packageManager.getPackageInfo("com.prolauncher.airtouch", 0); true }.getOrDefault(false)
        if (hasLegacyConflictPkg) {
            val delBtn = createBtn("⚠ [구버전 충돌 앱 감지됨] 터치하여 이전 AirTouch 삭제하기", false) {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:com.prolauncher.airtouch")))
            }
            delBtn.setBackgroundColor(Color.parseColor("#B71C1C")); root.addView(delBtn)
        }

        // 1. 통합 시스템 상태 카드 (권한 + 엔진 상태 + RAM 사용량 단일 통합)
        statusText = TextView(this).apply {
            textSize = 12.8f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#181B20")); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(statusText)

        // 2. 한눈에 보는 4대 기본 동작 안내 카드 (색상·손모양 완전 분리 설명)
        root.addView(TextView(this).apply {
            text = "[ 기본 제스처 4가지 — 서로 간섭 없이 완전 분리 동작 ]\n" +
                "• \u261D\uFE0F 포인터 이동 (초록 링) : 검지 위주로 펴고 이동\n" +
                "• \uD83D\uDC4C 클릭 / 길게 (노란 링) : 엄지+검지 끝 살짝 맞대기 (0.65초 유지=롱프레스)\n" +
                "• \uD83D\uDD90\uFE0F 화면 스크롤 (하늘 링) : 손바닥(3손가락 이상) 펴면 커서 고정 후 상하좌우 쓸기\n" +
                "• \u270A 일시정지 토글 (빨간 링): 주먹을 0.6초 쥐면 정지 / 다시 0.6초 쥐면 재개"
            textSize = 12f; setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#131A22")); setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(10) }
        })

        // 3. 실행 및 권한 제어 버튼
        root.addView(createBtn("[ 1. 접근성 서비스(터치·스크롤 권한) 켜기 ]") { LauncherAirTouchBridge.openAccessibilitySettings(this) })
        root.addView(createBtn("[ 2. 전면 카메라 권한 허용 ]") { requestCameraAndNotifPermissions(1001) })
        toggleBtn = createBtn("[ 3. AirTouch 제스처 추적 시작 ]", true) {
            if (!hasCameraPermission()) { requestCameraAndNotifPermissions(1002); return@createBtn }
            if (!LauncherAirTouchBridge.isAccessibilityEnabled(this)) {
                Toast.makeText(this, "접근성 설정에서 'AirTouch 제스처 컨트롤'을 켜주세요.", Toast.LENGTH_LONG).show()
                LauncherAirTouchBridge.openAccessibilitySettings(this); return@createBtn
            }
            LauncherAirTouchBridge.toggle(this)
            uiHandler.postDelayed({ refreshDashboardState() }, 140L)
        }
        root.addView(toggleBtn)
        root.addView(createBtn("[ 커서 위치 화면 정중앙으로 리셋 ]") { LauncherAirTouchBridge.recenter(this) })

        // 4. 통합 클릭 & 스크롤 연습 타겟
        practiceBtn = createBtn("★ [기본 기능 테스트] 커서로 여기를 \uD83D\uDC4C클릭(0회) 하거나 \uD83D\uDD90\uFE0F손바닥으로 이 화면을 위아래 스크롤해 보세요!") {
            practiceClickCount++
            updatePracticeButtonText()
            practiceBtn.setBackgroundColor(Color.parseColor("#1B5E20"))
        }
        root.addView(practiceBtn)

        // 5. 레이아웃 표시 ON/OFF 토글 3종
        root.addView(createHeader("DISPLAY LAYOUT TOGGLES (오버레이 및 모니터 표시 설정)"))
        root.addView(Switch(this).apply {
            text = "우측 하단 21개 손가락 관절 미니 뷰 표시"
            setTextColor(Color.WHITE); isChecked = config.showSkeletonMiniView; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.showSkeletonMiniView = checked; AirTouchAccessibilityService.instance?.refreshOverlayVisibility() }
        })
        root.addView(Switch(this).apply {
            text = "상단 플로팅 현재 상태 배지 표시"
            setTextColor(Color.WHITE); isChecked = config.showOverlayStatusPill; setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> config.showOverlayStatusPill = checked; AirTouchAccessibilityService.instance?.refreshOverlayVisibility() }
        })
        root.addView(Switch(this).apply {
            text = "앱 내 실시간 인식 상태 모니터 표시"
            setTextColor(Color.WHITE); isChecked = config.showLiveMonitorCard; setPadding(0, dp(6), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                config.showLiveMonitorCard = checked
                val vis = if (checked) View.VISIBLE else View.GONE
                monitorHeaderView.visibility = vis; liveTelemetryText.visibility = vis
            }
        })

        monitorHeaderView = createHeader("LIVE MONITOR (실시간 모드 · 관절 · 거리 상태)")
        liveTelemetryText = TextView(this).apply {
            textSize = 12.4f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#FFD740"))
            setBackgroundColor(Color.parseColor("#141920")); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val initVis = if (config.showLiveMonitorCard) View.VISIBLE else View.GONE
        monitorHeaderView.visibility = initVis; liveTelemetryText.visibility = initVis
        root.addView(monitorHeaderView); root.addView(liveTelemetryText)

        // 6. 핵심 감도 설정 (불필요한 중복 스위치 제거 후 3대 핵심 슬라이더만 명확히 배치)
        root.addView(createHeader("CORE SENSITIVITY (포인터 · 클릭 · 손바닥 스크롤 감도)"))

        speedLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12.5f }
        root.addView(speedLabel)
        speedSeek = SeekBar(this).apply {
            max = 260
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.6f + (p / 100f)
                    if (fromUser) config.cursorSpeed = v
                    speedLabel.text = "1) \u261D\uFE0F 포인터 이동 속도: ${String.format("%.2f", v)}x"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(speedSeek)

        pinchLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12.5f; setPadding(0, dp(10), 0, 0) }
        root.addView(pinchLabel)
        pinchSeek = SeekBar(this).apply {
            max = 28
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.18f + (p / 100f)
                    if (fromUser) config.pinchRatioThreshold = v
                    pinchLabel.text = "2) \uD83D\uDC4C 핀치 클릭 민감도: ${String.format("%.2f", v)} (높일수록 살짝만 맞대도 클릭)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(pinchSeek)

        scrollSensLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12.5f; setPadding(0, dp(10), 0, 0) }
        root.addView(scrollSensLabel)
        scrollSeek = SeekBar(this).apply {
            max = 105
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.045f + (p / 1000f)
                    if (fromUser) config.palmSwipeThreshold = v
                    scrollSensLabel.text = "3) \uD83D\uDD90\uFE0F 손바닥 스크롤 요구 폭: ${String.format("%.3f", v)} (낮출수록 가볍게 쓸어도 스크롤)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(scrollSeek)

        swipeSwitch = Switch(this).apply {
            text = "\uD83D\uDD90\uFE0F 손바닥 스크롤 기능 사용"
            setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> config.swipeScrollEnabled = checked }
        }
        root.addView(swipeSwitch)

        invertSwitch = Switch(this).apply {
            text = "손바닥 상하 스크롤 방향 반전 (기본 OFF: 손을 위로 쓸면 아래 내용 보기)"
            setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.invertPalmScroll = checked }
        }
        root.addView(invertSwitch)

        // 7. 손바닥·포인팅과 절대 겹치지 않는 5종 고유 단축 포즈
        root.addView(createHeader("5 DISTINCT SHORTCUT POSES (0.6초 정지 시 실행 · 터치하여 변경)"))
        poseUpdaters.clear()
        for (pose in HandPose.values()) {
            val rowBtn = createBtn("") {}
            val updater: () -> Unit = {
                rowBtn.text = "${pose.labelKo}  ->  [${config.getActionForPose(pose).titleKo}]\n  • ${pose.howToKo}"
            }
            poseUpdaters.add(updater)
            updater()
            rowBtn.setOnClickListener {
                val actions = AirTouchAction.values()
                AlertDialog.Builder(this)
                    .setTitle("${pose.labelKo} 실행 동작 선택")
                    .setItems(actions.map { it.titleKo }.toTypedArray()) { _, w ->
                        config.setActionForPose(pose, actions[w])
                        updater()
                    }
                    .show()
            }
            root.addView(rowBtn)
        }

        root.addView(createBtn("[ 모든 감도 및 단축 포즈 기본값으로 초기화 ]") {
            config.resetDefaults()
            syncControlsFromConfig()
            Toast.makeText(this, "기본 설정으로 초기화되었습니다.", Toast.LENGTH_SHORT).show()
        })

        syncControlsFromConfig()
        scroll.addView(root)
        return scroll
    }

    private fun syncControlsFromConfig() {
        speedSeek.progress = ((config.cursorSpeed - 0.6f) * 100f).roundToInt().coerceIn(0, 260)
        speedLabel.text = "1) \u261D\uFE0F 포인터 이동 속도: ${String.format("%.2f", config.cursorSpeed)}x"
        pinchSeek.progress = ((config.pinchRatioThreshold - 0.18f) * 100f).roundToInt().coerceIn(0, 28)
        pinchLabel.text = "2) \uD83D\uDC4C 핀치 클릭 민감도: ${String.format("%.2f", config.pinchRatioThreshold)} (높일수록 살짝만 맞대도 클릭)"
        scrollSeek.progress = ((config.palmSwipeThreshold - 0.045f) * 1000f).roundToInt().coerceIn(0, 105)
        scrollSensLabel.text = "3) \uD83D\uDD90\uFE0F 손바닥 스크롤 요구 폭: ${String.format("%.3f", config.palmSwipeThreshold)} (낮출수록 가볍게 쓸어도 스크롤)"
        swipeSwitch.isChecked = config.swipeScrollEnabled
        invertSwitch.isChecked = config.invertPalmScroll
        poseUpdaters.forEach { it() }
    }

    private fun updatePracticeButtonText() {
        practiceBtn.text = "★ [통합 테스트] 클릭 성공: ${practiceClickCount}회  |  손바닥 스크롤 누적: ${AirTouchTelemetry.scrollCount}회"
    }

    private fun updateLiveTelemetryCard() {
        updatePracticeButtonText()
        if (!config.showLiveMonitorCard) return
        if (!LauncherAirTouchBridge.isRunning()) {
            liveTelemetryText.text = "• 대기 중 ('3. AirTouch 제스처 추적 시작'을 눌러주세요)"
            return
        }
        liveTelemetryText.text =
            "• 손 인식 : ${if (AirTouchTelemetry.handDetected) "● 정상 (${AirTouchTelemetry.cameraResolutionText} · 지연 ${AirTouchTelemetry.inferenceLatencyMs}ms)" else "○ 카메라 시야에 손 없음"}\n" +
            "• 거리/방향: 약 ${AirTouchTelemetry.estimatedDistanceCm}cm · ${AirTouchTelemetry.orientationText}\n" +
            "• 손가락  : ${AirTouchTelemetry.fingerStatesText}\n" +
            "• 현재모드: ${AirTouchTelemetry.currentModeText}\n" +
            "• 핀치비율: ${String.format("%.2f", AirTouchTelemetry.pinchRatio)} (임계값 ${String.format("%.2f", config.pinchRatioThreshold)}) · 최근 실행: ${AirTouchTelemetry.lastTriggeredActionText}"
    }

    private fun refreshDashboardState() {
        val running = LauncherAirTouchBridge.isRunning()
        lastObservedRunning = running
        val pssMb = runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss / 1024
        }.getOrDefault(0)
        statusText.text =
            "• 카메라 권한 : ${if (hasCameraPermission()) "허용됨 (OK)" else "미허용 (터치하여 허용)"}\n" +
            "• 접근성 권한 : ${if (LauncherAirTouchBridge.isAccessibilityEnabled(this)) "활성화됨 (OK)" else "비활성 (설정 필요)"}\n" +
            "• 엔진 & RAM  : ${if (running) "실행 중 (PSS 약 ${pssMb}MB)" else "중지됨 (카메라·모델 0MB 유휴 상태)"}"
        toggleBtn.text = if (running) "[ AirTouch 제스처 추적 중지 (RAM 즉시 반환) ]" else "[ 3. AirTouch 제스처 추적 시작 ]"
        updateLiveTelemetryCard()
    }

    private fun requestCameraAndNotifPermissions(reqCode: Int) {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), reqCode)
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun createHeader(title: String): TextView = TextView(this).apply { text = title; textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676")); setPadding(0, dp(18), 0, dp(6)) }
    private fun createBtn(label: String, isPrimary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 13f
        setTextColor(if (isPrimary) Color.BLACK else Color.WHITE); setBackgroundColor(if (isPrimary) Color.parseColor("#00E676") else Color.parseColor("#1E2229"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { onClick() }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

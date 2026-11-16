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
 * 12종 손가락 제스처 및 핀치 줌에서 실행 가능한 전체 시스템·미디어·화면 확대/축소 액션
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
    ZOOM_IN_AT_CURSOR("커서 위치 화면 확대 (Pinch Zoom In +)"),
    ZOOM_OUT_AT_CURSOR("커서 위치 화면 축소 (Pinch Zoom Out -)"),
    DOUBLE_TAP_AT_CURSOR("커서 위치 더블 클릭"),
    MEDIA_PLAY_PAUSE("미디어 재생 / 일시정지"),
    MEDIA_NEXT("다음 곡 / 영상"),
    MEDIA_PREV("이전 곡 / 영상"),
    VOLUME_UP("볼륨 높이기 (+)"),
    VOLUME_DOWN("볼륨 낮추기 (-)"),
    TOGGLE_PAUSE_TRACKING("커서 일시정지 / 재개 토글")
}

/**
 * 손가락 5개 조합으로 구분 가능한 최대 12가지 정적 손 모양 제스처 (0.6초 정지 시 발동)
 */
enum class HandPose(val key: String, val labelKo: String, val howToKo: String, val defaultAction: AirTouchAction) {
    PEACE("pose_v31_peace", "\u270C\uFE0F 브이 (검지+중지)", "약지·새끼는 접고 검지·중지 2개만 편 채 0.6초 정지", AirTouchAction.GLOBAL_BACK),
    L_GUN("pose_v31_lgun", "\uD83D\uDC49 총 모양 L (엄지+검지)", "중지·약지·새끼는 접고 엄지·검지를 ㄱ자로 활짝 펴서 0.6초 정지", AirTouchAction.ZOOM_IN_AT_CURSOR),
    PINCH_THREE("pose_v31_p3", "\uD83E\uDD1F 스파이더맨 (엄지+검지+새끼)", "중지·약지만 접고 엄지·검지·새끼 3개를 편 채 0.6초 정지", AirTouchAction.ZOOM_OUT_AT_CURSOR),
    THREE_FINGERS("pose_v31_three", "\u261D\uFE0F 세 손가락 (검지+중지+약지)", "엄지·새끼는 접고 검지·중지·약지 3개를 편 채 0.6초 정지", AirTouchAction.GLOBAL_RECENTS),
    THUMB_INDEX_MID("pose_v31_tim", "\u270B 삼지 포즈 (엄지+검지+중지)", "약지·새끼는 접고 엄지·검지·중지 3개를 편 채 0.6초 정지", AirTouchAction.GLOBAL_NOTIFICATIONS),
    FOUR_FINGERS("pose_v31_four", "\uD83D\uDD96 네 손가락 (엄지만 접음)", "엄지만 접고 나머지 4손가락을 편 채 제자리에서 0.6초 정지", AirTouchAction.VOLUME_UP),
    OPEN_PALM("pose_v31_palm", "\uD83D\uDD90\uFE0F 손바닥 활짝 (5개 모두 폄)", "다섯 손가락을 모두 편 채 흔들지 않고 제자리 0.6초 정지", AirTouchAction.GLOBAL_HOME),
    ROCK_ON("pose_v31_rock", "\uD83E\uDD18 락앤롤 (검지+새끼)", "엄지·중지·약지는 접고 검지·새끼만 편 채 0.6초 정지", AirTouchAction.GLOBAL_LOCK_SCREEN),
    SHAKA("pose_v31_shaka", "\uD83E\uDD19 샤카/전화 (엄지+새끼)", "검지·중지·약지는 접고 엄지·새끼만 편 채 0.6초 정지", AirTouchAction.VOLUME_DOWN),
    THUMB_UP("pose_v31_thumb", "\uD83D\uDC4D 엄지 척 (엄지만 폄)", "네 손가락을 모두 접고 엄지만 편 채 0.6초 정지", AirTouchAction.MEDIA_PLAY_PAUSE),
    PINKY_ONLY("pose_v31_pinky", "\uD83E\uDD1E 새끼손가락 약속 (새끼만 폄)", "엄지·검지·중지·약지를 접고 새끼손가락만 편 채 0.6초 정지", AirTouchAction.GLOBAL_SCREENSHOT),
    FIST("pose_v31_fist", "\u270A 주먹 쥐기 (5개 모두 접음)", "다섯 손가락을 모두 말아 쥔 채 0.6초 정지", AirTouchAction.TOGGLE_PAUSE_TRACKING)
}

enum class ActiveGestureMode {
    PAUSED, PINCH_CLICK, PINCH_ZOOM, PALM_SCROLL, SHORTCUT_POSE, POINTER_MOVE
}

class AirTouchConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("airtouch_lowram_prefs", Context.MODE_PRIVATE)

    var cursorSpeed: Float
        get() = prefs.getFloat("cursor_speed_v30", 1.75f)
        set(v) = prefs.edit().putFloat("cursor_speed_v30", v.coerceIn(0.6f, 3.2f)).apply()

    // v3.2: 손끝 지문 패드 중심 간 물리적 간격(0.33~0.39)을 반영하여 기본 임계값을 0.40f로 상향 (범위 0.22~0.56)
    var pinchRatioThreshold: Float
        get() = prefs.getFloat("pinch_ratio_v32", 0.40f)
        set(v) = prefs.edit().putFloat("pinch_ratio_v32", v.coerceIn(0.22f, 0.56f)).apply()

    var pinchZoomEnabled: Boolean
        get() = prefs.getBoolean("pinch_zoom_enabled_v31", true)
        set(v) = prefs.edit().putBoolean("pinch_zoom_enabled_v31", v).apply()

    var swipeScrollEnabled: Boolean
        get() = prefs.getBoolean("swipe_scroll_v30", true)
        set(v) = prefs.edit().putBoolean("swipe_scroll_v30", v).apply()

    var palmSwipeThreshold: Float
        get() = prefs.getFloat("palm_swipe_thresh_v30", 0.072f)
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
            .putFloat("pinch_ratio_v32", 0.40f)
            .putBoolean("pinch_zoom_enabled_v31", true)
            .putBoolean("swipe_scroll_v30", true)
            .putFloat("palm_swipe_thresh_v30", 0.072f)
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
    @Volatile var clickCount: Int = 0
    @Volatile var scrollCount: Int = 0
    @Volatile var zoomCount: Int = 0
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
    enum class ZoomDirection { NONE, IN, OUT }

    data class FrameEvaluation(
        val mode: ActiveGestureMode,
        val normCursorX: Float,
        val normCursorY: Float,
        val isPinching: Boolean,
        val gaugeProgress: Float,
        val tapClicked: Boolean,
        val longPressed: Boolean,
        val zoomDirection: ZoomDirection,
        val zoomScaleStep: Float,
        val triggeredPoseAction: AirTouchAction?,
        val scrollDirection: ScrollDirection,
        val scrollStrength: Float,
        val activeFeedbackBadge: String,
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

    // 핀치 클릭 & 핀치 줌(Zoom) 상태 (v3.2 정밀 클릭 엔진)
    private var prevPinchRatio = 1.0f
    private var isCurrentlyPinching = false
    private var pinchStartTimeMs = 0L
    private var pinchStartPalmY = 0.5f
    private var pinchZoomLatched = false
    private var zoomFiredInCurrentPinch = false
    private var zoomAnchorPalmY = 0.5f
    private var lastZoomStepTimeMs = 0L
    private var lastTapClickTimeMs = 0L
    private var longPressTriggered = false
    private var postPinchFreezeUntilMs = 0L

    // 다중 프레임 손바닥 스크롤 & 공중 소프트 탭 임펄스 & 두 손가락 줌 슬라이딩 윈도우
    private val bufSize = 12
    private val palmBufX = FloatArray(bufSize)
    private val palmBufY = FloatArray(bufSize)
    private val pinchRatioBuf = FloatArray(bufSize)
    private val palmBufTs = LongArray(bufSize)
    private var palmBufHead = 0
    private var palmBufCount = 0
    private var prevSmoothPalmX = -1f; private var prevSmoothPalmY = -1f
    private var lastScrollHandOpenMs = 0L
    private var lastTwoFingerZoomReadyMs = 0L
    private var lastSwipeTriggerTimeMs = 0L
    private var lastScrollDir = ScrollDirection.NONE
    private var feedbackBadgeUntilMs = 0L
    private var feedbackBadgeLabel = ""

    private var currentCandidatePose: HandPose? = null
    private var poseStartTimeMs = 0L
    private var poseTriggeredInHold = false

    private val landmarkRing = Array(4) { FloatArray(42) }
    private var landmarkRingIdx = 0

    @Volatile var isTrackingPaused: Boolean = false; private set

    fun recenterCursor() { cursorX = 0.5f; cursorY = 0.5f }

    /**
     * 핵심 수정 1 (다중 지점 지문 패드 거리 계산):
     * 손끝 끝점(#4-#8)뿐 아니라 실제 집게 클릭 시 자주 맞닿는 엄지 지문(#4/#3 보간)과 검지 첫째 마디 패드(#7/#8 보간) 중
     * 최소 접촉 거리를 계산하여 옆모습·원거리에서도 자연스러운 손가락 맞댐을 100% 포착합니다.
     */
    private fun computeEffectivePinchRatio(lm: List<NormalizedLandmark>, handScale: Float): Float {
        val thumbIp = lm[3]; val thumbTip = lm[4]
        val indexDip = lm[7]; val indexTip = lm[8]
        val tipToTip = dist2D(thumbTip, indexTip)
        val tipToIndexDip = dist2D(thumbTip, indexDip) * 1.05f
        val thumbPadX = thumbTip.x() * 0.75f + thumbIp.x() * 0.25f
        val thumbPadY = thumbTip.y() * 0.75f + thumbIp.y() * 0.25f
        val indexPadX = indexTip.x() * 0.65f + indexDip.x() * 0.35f
        val indexPadY = indexTip.y() * 0.65f + indexDip.y() * 0.35f
        val padToPad = hypot(thumbPadX - indexPadX, thumbPadY - indexPadY) * 1.02f
        return minOf(tipToTip, tipToIndexDip, padToPad) / handScale
    }

    fun evaluate(lm: List<NormalizedLandmark>): FrameEvaluation {
        val now = SystemClock.uptimeMillis()
        val outLm = landmarkRing[landmarkRingIdx]
        landmarkRingIdx = (landmarkRingIdx + 1) % landmarkRing.size
        for (i in 0 until minOf(21, lm.size)) {
            outLm[i * 2] = lm[i].x()
            outLm[i * 2 + 1] = lm[i].y()
        }

        val wrist = lm[0]; val thumbTip = lm[4]; val indexMcp = lm[5]; val indexPip = lm[6]
        val indexDip = lm[7]; val indexTip = lm[8]; val middleMcp = lm[9]; val pinkyMcp = lm[17]

        val rawScale2D = maxOf(dist2D(wrist, middleMcp), dist2D(wrist, indexMcp) * 0.95f, dist2D(indexMcp, pinkyMcp) * 1.18f).coerceIn(0.05f, 0.55f)
        if (!handScaleInitialized || (now - lastValidEvalMs) > 600L) {
            smoothedHandScale2D = rawScale2D; handScaleInitialized = true
        } else {
            smoothedHandScale2D += (rawScale2D - smoothedHandScale2D) * 0.12f
        }
        val handScale = smoothedHandScale2D
        val estCm = (6.4f / handScale).roundToInt().coerceIn(15, 110)
        AirTouchTelemetry.estimatedDistanceCm = estCm
        val distGain = (0.185f / handScale).coerceIn(0.65f, 2.15f)

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

        val pinchRatio = computeEffectivePinchRatio(lm, handScale)
        val handReentered = (now - lastValidEvalMs) > 220L
        if (handReentered) {
            handEnteredFrameMs = now
            prevFingerX = smoothFingerX; prevFingerY = smoothFingerY
            prevKnuckleX = smoothKnuckleX; prevKnuckleY = smoothKnuckleY
            prevSmoothPalmX = palmX; prevSmoothPalmY = palmY
            prevPinchRatio = pinchRatio
            isCurrentlyPinching = false; pinchZoomLatched = false; zoomFiredInCurrentPinch = false
            palmBufCount = 0; palmBufHead = 0
        }
        val dtSec = ((now - lastValidEvalMs).coerceIn(8L, 100L)) / 1000f
        lastValidEvalMs = now

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
        // 핵심 수정 2 (주먹 오판 차단 해제):
        // 중지·약지·새끼를 접은 채 검지를 굽혀 엄지와 맞대는 '집게 클릭(\uD83E\uDD0F)'이 주먹(✊)으로 오판되어 클릭이 막히던 버그 수정!
        // 실제 주먹은 검지 끝이 손바닥 깊숙이 말려 들어가고 엄지가 중지 마디(#10) 위에 포개질 때만 주먹으로 인정합니다.
        val indexTuckedDeepInPalm = (dist2D(wrist, indexTip) < dist2D(wrist, indexMcp) * 0.85f) &&
            (dist2D(wrist, indexDip) < dist2D(wrist, indexPip) * 0.90f)
        val thumbRestingOnMiddleFist = (dist2D(thumbTip, lm[10]) / handScale < 0.28f) &&
            (dist2D(thumbTip, lm[10]) < dist2D(thumbTip, indexTip) * 0.92f)
        val isTightFist = !middleUp && !ringUp && !pinkyUp && indexTuckedDeepInPalm && thumbRestingOnMiddleFist

        // 릴리스 히스테리시스를 1.24배로 타이트하게 조여 빠른 연속 탭에서도 손가락을 살짝만 떼면 즉시 클릭 발동!
        val pinchActive = !isTightFist && (if (isCurrentlyPinching) pinchRatio < (ratioThreshold * 1.24f) else pinchRatio < ratioThreshold)
        val pinchFreeze = isCurrentlyPinching || pinchActive || (now < postPinchFreezeUntilMs) ||
            (pinchRatio < ratioThreshold * 1.20f) ||
            (pinchClosingRate > 0.012f && pinchRatio < ratioThreshold * 1.48f)

        val instStepX = if (prevSmoothPalmX >= 0f) (palmX - prevSmoothPalmX) else 0f
        val instStepY = if (prevSmoothPalmY >= 0f) (palmY - prevSmoothPalmY) else 0f
        val palmSpeedNorm = (hypot(instStepX, instStepY) / dtSec) * distGain
        prevSmoothPalmX = palmX; prevSmoothPalmY = palmY

        // 링버퍼에 손바닥 위치 및 엄지-검지 패드 비율 기록
        palmBufX[palmBufHead] = palmX
        palmBufY[palmBufHead] = palmY
        pinchRatioBuf[palmBufHead] = pinchRatio
        palmBufTs[palmBufHead] = now
        palmBufHead = (palmBufHead + 1) % bufSize
        if (palmBufCount < bufSize) palmBufCount++

        // 1) 핀치 클릭 + 길게 누르기 + [핀치 홀드 상하 드래그 줌] 정밀 분리 판정
        var tapClicked = false; var longPressed = false; var pinchProgress = 0f
        var zoomDir = ZoomDirection.NONE; var zoomScaleStep = 1.0f

        if (!isTrackingPaused && pinchActive) {
            if (!isCurrentlyPinching) {
                isCurrentlyPinching = true
                pinchStartTimeMs = now
                pinchStartPalmY = palmY
                zoomAnchorPalmY = palmY
                pinchZoomLatched = false
                zoomFiredInCurrentPinch = false
                longPressTriggered = false
            } else {
                val heldMs = now - pinchStartTimeMs
                val dragFromStartY = (palmY - pinchStartPalmY) * distGain
                // 핵심 수정 3 (핀치 줌의 클릭 가로채기 원천 차단):
                // 0.25초 미만의 짧은 클릭 중에는 손이 살짝 흔들려도 절대 줌 모드로 전환하지 않으며,
                // 0.25초 이상 유지하면서 의도적으로 상하 5cm 이상(0.110f) 끌었을 때만 드래그 줌으로 진입합니다!
                if (config.pinchZoomEnabled && !longPressTriggered && heldMs >= 250L && abs(dragFromStartY) >= 0.110f) {
                    pinchZoomLatched = true
                }
                if (pinchZoomLatched) {
                    val stepDy = (palmY - zoomAnchorPalmY) * distGain
                    if (abs(stepDy) >= 0.052f && (now - lastZoomStepTimeMs) > 250L) {
                        zoomDir = if (stepDy < 0f) ZoomDirection.IN else ZoomDirection.OUT
                        zoomScaleStep = (abs(stepDy) / 0.052f).coerceIn(1.0f, 1.75f)
                        zoomAnchorPalmY = palmY
                        zoomFiredInCurrentPinch = true
                        lastZoomStepTimeMs = now
                        postPinchFreezeUntilMs = now + 240L
                        AirTouchTelemetry.zoomCount++
                        feedbackBadgeLabel = if (zoomDir == ZoomDirection.IN) "\uD83D\uDD0D 화면 확대 (+)" else "\uD83D\uDD0D 화면 축소 (-)"
                        feedbackBadgeUntilMs = now + 550L
                        AirTouchTelemetry.lastTriggeredActionText = feedbackBadgeLabel
                    }
                } else {
                    pinchProgress = (heldMs.toFloat() / 680f).coerceIn(0f, 1f)
                    if (!longPressTriggered && heldMs >= 680L) {
                        longPressed = true; longPressTriggered = true
                        lastTapClickTimeMs = now; postPinchFreezeUntilMs = now + 220L
                        AirTouchTelemetry.clickCount++
                        feedbackBadgeLabel = "\uD83D\uDC4C 길게 누르기!"
                        feedbackBadgeUntilMs = now + 520L
                        AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 길게 누르기 (Long Press)"
                    }
                }
            }
        } else if (isCurrentlyPinching) {
            val heldMs = now - pinchStartTimeMs
            // 핵심 수정 4: 1프레임 초고속 탭(0ms~)도 누락 없이 클릭으로 인정하고, 실제 줌이 발동된 적이 없다면 100% 클릭 실행!
            if (!longPressTriggered && !zoomFiredInCurrentPinch && heldMs in 0L..679L &&
                !isTrackingPaused && (now - lastTapClickTimeMs) > 190L
            ) {
                tapClicked = true
                lastTapClickTimeMs = now
                postPinchFreezeUntilMs = now + 200L
                palmBufCount = 0; palmBufHead = 0
                AirTouchTelemetry.clickCount++
                feedbackBadgeLabel = "\uD83D\uDC4C 클릭!"
                feedbackBadgeUntilMs = now + 420L
                AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 단일 클릭 (Tap)"
            }
            isCurrentlyPinching = false
            pinchZoomLatched = false
            zoomFiredInCurrentPinch = false
        }

        // 핵심 수정 5 (원거리·가벼운 공중 소프트 탭 임펄스 보정):
        // 원거리에서 엄지·검지를 가볍게 '톡' 맞대었다 떼어 정적 임계값을 아슬아슬하게 스친 경우에도(급격한 오므림 후 즉시 반등) 클릭으로 보정!
        if (!isTrackingPaused && !isCurrentlyPinching && !tapClicked && !longPressed && !isTightFist &&
            (now - lastTapClickTimeMs) > 240L && (now - lastSwipeTriggerTimeMs) > 350L &&
            (now - lastZoomStepTimeMs) > 350L && (now - handEnteredFrameMs) > 180L &&
            palmSpeedNorm < 0.24f && palmBufCount >= 3
        ) {
            var valleyMin = 9f
            var valleyIdxOffset = -1
            for (k in 1 until minOf(palmBufCount, 6)) {
                val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
                val dtWin = now - palmBufTs[idx]
                if (dtWin > 220L) break
                val r = pinchRatioBuf[idx]
                if (r < valleyMin) { valleyMin = r; valleyIdxOffset = k }
            }
            if (valleyIdxOffset >= 1 && valleyMin < ratioThreshold * 1.15f && (pinchRatio - valleyMin) >= 0.055f) {
                var prePeak = valleyMin
                for (k in (valleyIdxOffset + 1) until palmBufCount) {
                    val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
                    if ((now - palmBufTs[idx]) > 340L) break
                    val r = pinchRatioBuf[idx]
                    if (r > prePeak) prePeak = r
                }
                if ((prePeak - valleyMin) >= 0.135f && prePeak >= ratioThreshold * 1.20f) {
                    tapClicked = true
                    lastTapClickTimeMs = now
                    postPinchFreezeUntilMs = now + 200L
                    palmBufCount = 0; palmBufHead = 0
                    AirTouchTelemetry.clickCount++
                    feedbackBadgeLabel = "\uD83D\uDC4C 클릭!"
                    feedbackBadgeUntilMs = now + 420L
                    AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 소프트 탭 클릭 (Tap)"
                }
            }
        }

        // 2) [두 손가락(엄지+검지) 공중 벌리기/오므리기 핀치 줌] — 클릭 구간과 완전히 분리된 넓은 간격에서만 동작!
        val isTwoFingerZoomPosture = !middleUp && !ringUp && !pinkyUp && indexUp && !isCurrentlyPinching
        if (isTwoFingerZoomPosture) lastTwoFingerZoomReadyMs = now
        val zoomFloor = (ratioThreshold * 1.32f).coerceIn(0.50f, 0.64f)
        if (config.pinchZoomEnabled && !isTrackingPaused && !tapClicked && !longPressed && zoomDir == ZoomDirection.NONE &&
            !isCurrentlyPinching && (now - lastTapClickTimeMs) > 360L && (now - lastTwoFingerZoomReadyMs) <= 180L &&
            (now - lastZoomStepTimeMs) > 360L && (now - handEnteredFrameMs) > 180L && palmSpeedNorm < 0.28f && palmBufCount >= 3
        ) {
            var maxRatioDelta = 0f
            for (k in 1 until palmBufCount) {
                val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
                val dtWin = now - palmBufTs[idx]
                if (dtWin < 55L) continue
                if (dtWin > 260L) break
                val rPrev = pinchRatioBuf[idx]
                if (rPrev >= zoomFloor && pinchRatio >= zoomFloor) {
                    val delta = pinchRatio - rPrev
                    if (abs(delta) > abs(maxRatioDelta)) maxRatioDelta = delta
                }
            }
            if (maxRatioDelta >= 0.36f && thumbUp) {
                zoomDir = ZoomDirection.IN
                zoomScaleStep = (maxRatioDelta / 0.36f).coerceIn(1.0f, 1.75f)
                lastZoomStepTimeMs = now; postPinchFreezeUntilMs = now + 240L
                palmBufCount = 0; palmBufHead = 0
                AirTouchTelemetry.zoomCount++
                feedbackBadgeLabel = "\uD83D\uDD0D 엄지·검지 벌림 확대 (+)"
                feedbackBadgeUntilMs = now + 550L
                AirTouchTelemetry.lastTriggeredActionText = feedbackBadgeLabel
            } else if (maxRatioDelta <= -0.38f && pinchRatio >= zoomFloor * 1.04f) {
                zoomDir = ZoomDirection.OUT
                zoomScaleStep = (abs(maxRatioDelta) / 0.38f).coerceIn(1.0f, 1.75f)
                lastZoomStepTimeMs = now; postPinchFreezeUntilMs = now + 240L
                palmBufCount = 0; palmBufHead = 0
                AirTouchTelemetry.zoomCount++
                feedbackBadgeLabel = "\uD83D\uDD0D 엄지·검지 오므림 축소 (-)"
                feedbackBadgeUntilMs = now + 550L
                AirTouchTelemetry.lastTriggeredActionText = feedbackBadgeLabel
            }
        }

        // 3) 손바닥 상하좌우 스크롤 판정 (손가락 3개 이상 펴고 움직일 때만 스크롤 발동)
        val isOpenPalmForScroll = indexUp && middleUp && ringUp && !isCurrentlyPinching && !pinchActive && (pinchRatio > ratioThreshold * 1.35f)
        if (isOpenPalmForScroll) lastScrollHandOpenMs = now

        var scrollDir = ScrollDirection.NONE
        var scrollStrength = 1.0f
        if (config.swipeScrollEnabled && !isTrackingPaused && !isCurrentlyPinching && !pinchFreeze && !tapClicked &&
            zoomDir == ZoomDirection.NONE && (now - handEnteredFrameMs) > 150L && (now - lastTapClickTimeMs) > 260L &&
            (now - lastScrollHandOpenMs) <= 250L && (now - lastSwipeTriggerTimeMs) > 280L && palmBufCount >= 2
        ) {
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
                        currentCandidatePose = null; poseTriggeredInHold = false
                        AirTouchTelemetry.scrollCount++
                        feedbackBadgeLabel = when (cand) {
                            ScrollDirection.DOWN -> "▼ 아래 스크롤"
                            ScrollDirection.UP -> "▲ 위로 스크롤"
                            ScrollDirection.LEFT -> "◀ 왼쪽 스와이프"
                            ScrollDirection.RIGHT -> "▶ 오른쪽 스와이프"
                            else -> ""
                        }
                        feedbackBadgeUntilMs = now + 520L
                        AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDD90\uFE0F $feedbackBadgeLabel"
                    }
                }
            }
        }

        // 4) 12종 손가락 정적 제스처 판정 (손이 멈춰 있을 때만 0.6초 충전)
        val isSingleIndexPointing = !thumbUp && indexUp && !middleUp && !ringUp && !pinkyUp
        val rawPose = if (isCurrentlyPinching || pinchFreeze || tapClicked || isSingleIndexPointing ||
            (now - lastTapClickTimeMs) < 450L || (now - lastSwipeTriggerTimeMs) < 850L || (now - lastZoomStepTimeMs) < 850L
        ) {
            null
        } else when {
            isTightFist && !thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.FIST
            isTrackingPaused -> null
            !thumbUp && indexUp && middleUp && !ringUp && !pinkyUp -> HandPose.PEACE
            thumbUp && indexUp && !middleUp && !ringUp && !pinkyUp && (pinchRatio > maxOf(ratioThreshold * 1.68f, 0.68f)) -> HandPose.L_GUN
            thumbUp && indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.PINCH_THREE
            !thumbUp && indexUp && middleUp && ringUp && !pinkyUp -> HandPose.THREE_FINGERS
            thumbUp && indexUp && middleUp && !ringUp && !pinkyUp -> HandPose.THUMB_INDEX_MID
            !thumbUp && indexUp && middleUp && ringUp && pinkyUp -> HandPose.FOUR_FINGERS
            thumbUp && indexUp && middleUp && ringUp && pinkyUp -> HandPose.OPEN_PALM
            !thumbUp && indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.ROCK_ON
            thumbUp && !indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.SHAKA
            thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.THUMB_UP
            !thumbUp && !indexUp && !middleUp && !ringUp && pinkyUp -> HandPose.PINKY_ONLY
            else -> null
        }

        var poseProgress = 0f
        var triggeredPoseAction: AirTouchAction? = null
        if (rawPose != null && palmSpeedNorm < 0.12f) {
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
                        if (mapped == AirTouchAction.ZOOM_IN_AT_CURSOR || mapped == AirTouchAction.ZOOM_OUT_AT_CURSOR) {
                            AirTouchTelemetry.zoomCount++
                            feedbackBadgeLabel = if (mapped == AirTouchAction.ZOOM_IN_AT_CURSOR) "\uD83D\uDD0D 포즈 확대 (+)" else "\uD83D\uDD0D 포즈 축소 (-)"
                            feedbackBadgeUntilMs = now + 550L
                        }
                        AirTouchTelemetry.lastTriggeredActionText = "${rawPose.labelKo} -> ${mapped.titleKo}"
                    }
                }
            }
        } else if (palmSpeedNorm >= 0.15f || rawPose == null) {
            currentCandidatePose = null; poseTriggeredInHold = false
        }

        // 5) 마우스 포인터 이동 (클릭 접근·클릭 중·스크롤·포즈 충전 중에는 0.0px 완벽 고정!)
        val inPalmScrollMotion = isOpenPalmForScroll && palmSpeedNorm >= 0.16f
        val freezeForPoseHold = poseProgress > 0.20f
        if (!isTrackingPaused && !inPalmScrollMotion && !handReentered && !pinchFreeze && !freezeForPoseHold && (now - lastSwipeTriggerTimeMs) > 220L) {
            val dFingerX = smoothFingerX - prevFingerX; val dFingerY = smoothFingerY - prevFingerY
            val dKnuckleX = smoothKnuckleX - prevKnuckleX; val dKnuckleY = smoothKnuckleY - prevKnuckleY
            val nearPinchBlend = if (pinchRatio < ratioThreshold * 1.42f) {
                ((pinchRatio - ratioThreshold * 1.20f) / (ratioThreshold * 0.22f)).coerceIn(0.15f, 1.0f)
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

        val activeBadge = if (now < feedbackBadgeUntilMs) feedbackBadgeLabel else ""
        val isClickFlash = activeBadge.startsWith("\uD83D\uDC4C")
        val isZoomFlash = activeBadge.startsWith("\uD83D\uDD0D")
        val mode = when {
            isTrackingPaused -> ActiveGestureMode.PAUSED
            pinchZoomLatched || isZoomFlash -> ActiveGestureMode.PINCH_ZOOM
            isCurrentlyPinching || isClickFlash -> ActiveGestureMode.PINCH_CLICK
            inPalmScrollMotion || (activeBadge.isNotEmpty() && !isZoomFlash && !isClickFlash) -> ActiveGestureMode.PALM_SCROLL
            poseProgress > 0.15f && currentCandidatePose != null -> ActiveGestureMode.SHORTCUT_POSE
            else -> ActiveGestureMode.POINTER_MOVE
        }

        val summary = when (mode) {
            ActiveGestureMode.PAUSED -> "[일시정지] \u270A 주먹 0.6초 쥐어 재개"
            ActiveGestureMode.PINCH_ZOOM -> if (activeBadge.isNotEmpty()) activeBadge else "\uD83D\uDD0D 핀치 드래그 줌 모드 (손을 위로=확대 / 아래로=축소)"
            ActiveGestureMode.PINCH_CLICK -> if (isClickFlash) activeBadge else "\uD83D\uDC4C 클릭 준비됨 (${estCm}cm · 바로 떼면 클릭 / 길게 잡고 끌면 줌)"
            ActiveGestureMode.PALM_SCROLL -> if (activeBadge.isNotEmpty()) "\uD83D\uDD90\uFE0F $activeBadge" else "\uD83D\uDD90\uFE0F 손바닥 스크롤 중 (${estCm}cm)"
            ActiveGestureMode.SHORTCUT_POSE -> "${currentCandidatePose!!.labelKo} (${(poseProgress * 100).toInt()}%)"
            ActiveGestureMode.POINTER_MOVE -> if (nearEdge) "손을 카메라 안쪽으로 옮겨주세요 (${estCm}cm)" else "\u261D\uFE0F 포인터 추적 중 (${estCm}cm · \uD83D\uDC4C클릭·줌 / \uD83D\uDD90\uFE0F스크롤)"
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
            zoomDirection = zoomDir,
            zoomScaleStep = zoomScaleStep,
            triggeredPoseAction = triggeredPoseAction,
            scrollDirection = scrollDir,
            scrollStrength = scrollStrength,
            activeFeedbackBadge = activeBadge,
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
        return (dist2D(tip, lm[5]) / handScale > 0.46f) && (dist2D(tip, lm[9]) / handScale > 0.55f) && (dist2D(wrist, tip) > dist2D(wrist, mcp) * 1.08f) && cos > 0.38f
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
    var feedbackBadge = ""
    var showStatusPill = true; var showSkeletonMini = true

    fun updateState(
        x: Float, y: Float, mode: ActiveGestureMode, handFound: Boolean, edgeWarn: Boolean, estCm: Int,
        landmarks: FloatArray?, progress: Float, label: String, badge: String, showPill: Boolean, showMini: Boolean
    ) {
        targetX = x; targetY = y; currentMode = mode; handVisible = handFound; nearEdge = edgeWarn; estimatedCm = estCm
        if (landmarks != null && landmarks.size >= 42) System.arraycopy(landmarks, 0, lmPts, 0, 42)
        gaugeProgress = progress; statusLabel = label; feedbackBadge = badge
        showStatusPill = showPill; showSkeletonMini = showMini
        if (mode == ActiveGestureMode.PINCH_CLICK || mode == ActiveGestureMode.PINCH_ZOOM || hypot(targetX - renderX, targetY - renderY) > 360f) {
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
        if (currentMode == ActiveGestureMode.PINCH_CLICK || currentMode == ActiveGestureMode.PINCH_ZOOM || hypot(dx, dy) < 0.35f) {
            renderX = targetX; renderY = targetY
        } else {
            val blend = (1.0f - exp(-36.0f * dt)).coerceIn(0.18f, 0.85f)
            renderX += dx * blend; renderY += dy * blend
            postInvalidateOnAnimation()
        }

        val isPinch = currentMode == ActiveGestureMode.PINCH_CLICK
        val isZoom = currentMode == ActiveGestureMode.PINCH_ZOOM
        val isPalm = currentMode == ActiveGestureMode.PALM_SCROLL
        val r = if (isPinch) 20f else if (isZoom) 32f else 28f
        val themeColor = when (currentMode) {
            ActiveGestureMode.PAUSED -> Color.parseColor("#FF5252")
            ActiveGestureMode.PINCH_ZOOM -> Color.parseColor("#E040FB")
            ActiveGestureMode.PINCH_CLICK -> Color.parseColor("#FFD740")
            ActiveGestureMode.PALM_SCROLL -> Color.parseColor("#00E5FF")
            ActiveGestureMode.SHORTCUT_POSE -> Color.parseColor("#40C4FF")
            ActiveGestureMode.POINTER_MOVE -> Color.parseColor("#00E676")
        }
        outerRingPaint.color = themeColor
        canvas.drawCircle(renderX, renderY, r, cursorShadowPaint)
        canvas.drawCircle(renderX, renderY, r, outerRingPaint)

        if (isPalm || isZoom) {
            val arm = 9f * density
            canvas.drawLine(renderX - arm, renderY, renderX + arm, renderY, outerRingPaint)
            canvas.drawLine(renderX, renderY - arm, renderX, renderY + arm, outerRingPaint)
        } else {
            centerDotPaint.color = if (isPinch) Color.parseColor("#FFD740") else Color.WHITE
            canvas.drawCircle(renderX, renderY, if (isPinch) 11f else 5.5f, centerDotPaint)
        }

        if (gaugeProgress > 0.04f) {
            arcRect.set(renderX - r - 11f, renderY - r - 11f, renderX + r + 11f, renderY + r + 11f)
            canvas.drawArc(arcRect, -90f, 360f * gaugeProgress, false, progressArcPaint)
        }

        val tagText = if (feedbackBadge.isNotEmpty()) feedbackBadge else if (isZoom) "줌 모드" else if (isPalm) "스크롤 모드" else ""
        if (tagText.isNotEmpty()) {
            cursorBadgePaint.color = when {
                tagText.startsWith("\uD83D\uDC4C") -> Color.parseColor("#FFD740")
                isZoom || tagText.startsWith("\uD83D\uDD0D") -> Color.parseColor("#EA80FC")
                else -> Color.parseColor("#00E5FF")
            }
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
            val msg = if (currentMode == ActiveGestureMode.PAUSED) "일시정지됨" else "손 탐색 중..."
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
            val isKey = (j == 4 || j == 7 || j == 8 || j == 5 || j == 0)
            canvas.drawCircle(jx, jy, (if (isKey) 3.4f else 2.1f) * density, if (isKey) keyJointPaint else jointPaint)
        }
        val modeTag = when (currentMode) {
            ActiveGestureMode.PINCH_ZOOM -> "핀치줌 · ${estimatedCm}cm"
            ActiveGestureMode.PALM_SCROLL -> "스크롤 · ${estimatedCm}cm"
            ActiveGestureMode.PINCH_CLICK -> "클릭 · ${estimatedCm}cm"
            ActiveGestureMode.PAUSED -> "일시정지"
            else -> "인식OK · ${estimatedCm}cm"
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

    fun notifyNoHandDetected(isPaused: Boolean) = mainHandler.post {
        val mode = if (isPaused) ActiveGestureMode.PAUSED else ActiveGestureMode.POINTER_MOVE
        val msg = if (isPaused) "[일시정지] \u270A 주먹 0.6초 쥐어 재개" else "전면 카메라 시야에 손을 보여주세요 (원거리 자동 부스트 탐색 중)"
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
            eval.landmarksXY, eval.gaugeProgress, eval.modeSummary, eval.activeFeedbackBadge,
            config.showOverlayStatusPill, config.showSkeletonMiniView
        )
        when {
            eval.tapClicked -> dispatchTap(px, py, 38L, retryIfBusy = true)
            eval.longPressed -> dispatchTap(px, py, 560L, retryIfBusy = true)
            eval.zoomDirection == GestureClassifier.ZoomDirection.IN -> dispatchPinchZoom(px, py, zoomIn = true, scale = eval.zoomScaleStep)
            eval.zoomDirection == GestureClassifier.ZoomDirection.OUT -> dispatchPinchZoom(px, py, zoomIn = false, scale = eval.zoomScaleStep)
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

    /**
     * 네이티브 2-Stroke 동시 대칭 멀티터치 핀치 줌 (지도·브라우저·갤러리 완벽 호환)
     */
    private fun dispatchPinchZoom(cx: Float, cy: Float, zoomIn: Boolean, scale: Float = 1.25f) {
        val minDim = minOf(screenWidth, screenHeight).toFloat()
        val safeCx = cx.coerceIn(screenWidth * 0.22f, screenWidth * 0.78f)
        val safeCy = cy.coerceIn(screenHeight * 0.22f, screenHeight * 0.78f)
        val innerDist = (minDim * 0.055f).coerceAtLeast(28f)
        val outerDist = (minDim * (0.17f * scale.coerceIn(1.0f, 1.6f))).coerceAtLeast(innerDist + 55f)
        val startOffset = if (zoomIn) innerDist else outerDist
        val endOffset = if (zoomIn) outerDist else innerDist
        val c45 = 0.7071f
        val p1 = Path().apply {
            moveTo(safeCx - startOffset * c45, safeCy - startOffset * c45)
            lineTo(safeCx - endOffset * c45, safeCy - endOffset * c45)
        }
        val p2 = Path().apply {
            moveTo(safeCx + startOffset * c45, safeCy + startOffset * c45)
            lineTo(safeCx + endOffset * c45, safeCy + endOffset * c45)
        }
        val dur = 240L
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, dur))
            .addStroke(GestureDescription.StrokeDescription(p2, 0L, dur))
            .build()
        safeDispatchGesture(desc, dur, retryOnceIfBusy = false)
    }

    private fun performShortcutAction(action: AirTouchAction, x: Float, y: Float) {
        when (action) {
            AirTouchAction.NONE, AirTouchAction.TOGGLE_PAUSE_TRACKING -> Unit
            AirTouchAction.ZOOM_IN_AT_CURSOR -> dispatchPinchZoom(x, y, zoomIn = true, scale = 1.35f)
            AirTouchAction.ZOOM_OUT_AT_CURSOR -> dispatchPinchZoom(x, y, zoomIn = false, scale = 1.35f)
            AirTouchAction.DOUBLE_TAP_AT_CURSOR -> {
                dispatchTap(x, y, 36L, retryIfBusy = true)
                mainHandler.postDelayed({ dispatchTap(x, y, 36L, retryIfBusy = true) }, 95L)
            }
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

    /**
     * 핵심 수정 6 (클릭 유실 방지 우선순위 큐잉):
     * 이전 제스처 스트로크 종료 직후 사용자가 바로 클릭했을 때 busy 가드에 막혀 클릭이 증발하지 않도록,
     * 클릭 제스처(retryOnceIfBusy=true)는 남은 시간 직후(12~130ms) 자동 재시도하여 100% 터치를 전달합니다.
     */
    private fun safeDispatchGesture(desc: GestureDescription, durationMs: Long, retryOnceIfBusy: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (now < gestureBusyUntilMs) {
            val waitMs = (gestureBusyUntilMs - now + 10L)
            if (retryOnceIfBusy && waitMs <= 140L) {
                mainHandler.postDelayed({ safeDispatchGesture(desc, durationMs, retryOnceIfBusy = false) }, waitMs.coerceAtLeast(12L))
            }
            return
        }
        gestureBusyUntilMs = now + durationMs + 15L
        val dispatched = dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { gestureBusyUntilMs = 0L }
            override fun onCancelled(gestureDescription: GestureDescription?) { gestureBusyUntilMs = 0L }
        }, mainHandler)
        if (!dispatched) gestureBusyUntilMs = 0L
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long, retryIfBusy: Boolean = true) {
        val maxX = (screenWidth - 2).coerceAtLeast(2).toFloat()
        val maxY = (screenHeight - 2).coerceAtLeast(2).toFloat()
        val p = Path().apply { moveTo(x.coerceIn(2f, maxX), y.coerceIn(2f, maxY)) }
        val desc = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build()
        safeDispatchGesture(desc, durationMs, retryOnceIfBusy = retryIfBusy)
    }

    private fun dispatchSwipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        safeDispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), durationMs, retryOnceIfBusy = false)
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
    private var farSearchToggle = false
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
                    .setMinHandDetectionConfidence(0.30f)
                    .setMinHandPresenceConfidence(0.34f)
                    .setMinTrackingConfidence(0.34f)
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
        val targetFps = when {
            AirTouchTelemetry.handDetected -> 30
            (now - lastHandDetectedTimeMs) < 12000L -> 20
            else -> 15
        }
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
                if (!AirTouchTelemetry.handDetected) {
                    farSearchToggle = !farSearchToggle
                    if (farSearchToggle) {
                        transformMatrix.postScale(1.38f, 1.38f)
                    }
                } else {
                    farSearchToggle = false
                }
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
            AirTouchTelemetry.fingerStatesText = "원거리 부스트 스캔 중..."
            AirTouchTelemetry.currentModeText = if (classifier.isTrackingPaused) "[일시정지 상태]" else "손 대기 중 (원거리 즉시 인식 활성화)"
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
            .setContentTitle("AirTouch AI v3.2 실행 중")
            .setContentText("초정밀 집게 클릭 · 멀티터치 핀치 줌 · 12종 손가락 제스처")
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
    private lateinit var zoomSwitch: Switch
    private lateinit var swipeSwitch: Switch
    private lateinit var invertSwitch: Switch
    private val poseUpdaters = mutableListOf<() -> Unit>()

    private var practiceTargetHitCount = 0
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
            text = "AIRTOUCH AI v3.2 (Build $vCode)"
            textSize = 18.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676"))
        })
        root.addView(TextView(this).apply {
            text = "초정밀 지문패드 클릭 엔진 + 원거리 즉시 인식 + 핀치 줌 + 12종 손가락 제스처"
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

        statusText = TextView(this).apply {
            textSize = 12.8f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#181B20")); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(statusText)

        // 핵심 제스처 & 개선된 클릭/핀치 줌 사용 안내 카드
        root.addView(TextView(this).apply {
            text = "[ 기본 동작 & v3.2 초정밀 클릭·핀치 줌 사용법 ]\n" +
                "• \u261D\uFE0F 포인터 이동 (초록 링) : 검지 위주로 펴고 이동 (원거리 85cm까지 즉시 인식)\n" +
                "• \uD83D\uDC4C 단일 클릭 / 길게 (노란 링) : 나머지 손가락을 펴든 접든 상관없이 엄지+검지 지문만 가볍게 '톡' 맞대었다 떼기! (0.68초 유지=롱프레스)\n" +
                "• \uD83D\uDD0D 핀치 줌 확대/축소 (보라 링) :\n" +
                "   1) 엄지+검지를 0.25초 이상 붙잡은 채 손을 위(+)/아래(-)로 5cm 끌기\n" +
                "   2) 또는 엄지+검지만 펴고 공중에서 넓게 벌리면 확대(+), 오므리면 축소(-)\n" +
                "• \uD83D\uDD90\uFE0F 손바닥 스크롤 (하늘 링) : 손가락 3개 이상 펴고 상하좌우로 가볍게 쓸기"
            textSize = 11.8f; setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#131A22")); setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(10) }
        })

        root.addView(createBtn("[ 1. 접근성 서비스(터치·스크롤·핀치줌 권한) 켜기 ]") { LauncherAirTouchBridge.openAccessibilitySettings(this) })
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

        practiceBtn = createBtn("★ [통합 테스트 과녁] 전체클릭: 0회 (과녁명중 0) | 스크롤: 0회 | 줌: 0회") {
            practiceTargetHitCount++
            updatePracticeButtonText()
            practiceBtn.setBackgroundColor(Color.parseColor("#1B5E20"))
        }
        root.addView(practiceBtn)

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

        monitorHeaderView = createHeader("LIVE MONITOR (실시간 모드 · 클릭비율 · 원거리 탐색 상태)")
        liveTelemetryText = TextView(this).apply {
            textSize = 12.4f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#FFD740"))
            setBackgroundColor(Color.parseColor("#141920")); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val initVis = if (config.showLiveMonitorCard) View.VISIBLE else View.GONE
        monitorHeaderView.visibility = initVis; liveTelemetryText.visibility = initVis
        root.addView(monitorHeaderView); root.addView(liveTelemetryText)

        root.addView(createHeader("CORE SENSITIVITY (포인터 · 클릭 · 핀치줌 · 스크롤 감도)"))

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
            max = 34
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = 0.22f + (p / 100f)
                    if (fromUser) config.pinchRatioThreshold = v
                    pinchLabel.text = "2) \uD83D\uDC4C 핀치 클릭 민감도: ${String.format("%.2f", v)} (기본 0.40 · 높일수록 살짝만 맞대도 클릭)"
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

        zoomSwitch = Switch(this).apply {
            text = "\uD83D\uDD0D 핀치 줌(화면 확대/축소) 제스처 사용 (0.25초 이상 핀치 홀드 후 상하 드래그 또는 엄지·검지 벌리기)"
            setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> config.pinchZoomEnabled = checked }
        }
        root.addView(zoomSwitch)

        swipeSwitch = Switch(this).apply {
            text = "\uD83D\uDD90\uFE0F 손바닥 스크롤 기능 사용"
            setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> config.swipeScrollEnabled = checked }
        }
        root.addView(swipeSwitch)

        invertSwitch = Switch(this).apply {
            text = "손바닥 상하 스크롤 방향 반전 (기본 OFF: 손을 위로 쓸면 아래 내용 보기)"
            setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> config.invertPalmScroll = checked }
        }
        root.addView(invertSwitch)

        // 12종 손가락 정적 제스처 전체 목록
        root.addView(createHeader("12 HAND GESTURE POSES (손을 멈추고 0.6초 유지 시 실행 · 터치하여 변경)"))
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

        root.addView(createBtn("[ 모든 감도 및 12종 제스처 기본값으로 초기화 ]") {
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
        pinchSeek.progress = ((config.pinchRatioThreshold - 0.22f) * 100f).roundToInt().coerceIn(0, 34)
        pinchLabel.text = "2) \uD83D\uDC4C 핀치 클릭 민감도: ${String.format("%.2f", config.pinchRatioThreshold)} (기본 0.40 · 높일수록 살짝만 맞대도 클릭)"
        scrollSeek.progress = ((config.palmSwipeThreshold - 0.045f) * 1000f).roundToInt().coerceIn(0, 105)
        scrollSensLabel.text = "3) \uD83D\uDD90\uFE0F 손바닥 스크롤 요구 폭: ${String.format("%.3f", config.palmSwipeThreshold)} (낮출수록 가볍게 쓸어도 스크롤)"
        zoomSwitch.isChecked = config.pinchZoomEnabled
        swipeSwitch.isChecked = config.swipeScrollEnabled
        invertSwitch.isChecked = config.invertPalmScroll
        poseUpdaters.forEach { it() }
    }

    private fun updatePracticeButtonText() {
        practiceBtn.text = "★ [통합 테스트 과녁] 전체클릭: ${AirTouchTelemetry.clickCount}회 (과녁명중 ${practiceTargetHitCount}) | 스크롤: ${AirTouchTelemetry.scrollCount}회 | 줌: ${AirTouchTelemetry.zoomCount}회"
    }

    private fun updateLiveTelemetryCard() {
        updatePracticeButtonText()
        if (!config.showLiveMonitorCard) return
        if (!LauncherAirTouchBridge.isRunning()) {
            liveTelemetryText.text = "• 대기 중 ('3. AirTouch 제스처 추적 시작'을 눌러주세요)"
            return
        }
        liveTelemetryText.text =
            "• 손 인식 : ${if (AirTouchTelemetry.handDetected) "● 정상 (${AirTouchTelemetry.cameraResolutionText} · 지연 ${AirTouchTelemetry.inferenceLatencyMs}ms)" else "○ 원거리 부스트 탐색 중 (1.38x 교차 스캔)"}\n" +
            "• 거리/방향: 약 ${AirTouchTelemetry.estimatedDistanceCm}cm · ${AirTouchTelemetry.orientationText}\n" +
            "• 손가락  : ${AirTouchTelemetry.fingerStatesText}\n" +
            "• 현재모드: ${AirTouchTelemetry.currentModeText}\n" +
            "• 클릭비율: ${String.format("%.2f", AirTouchTelemetry.pinchRatio)} (임계값 ${String.format("%.2f", config.pinchRatioThreshold)}) · 최근 실행: ${AirTouchTelemetry.lastTriggeredActionText}"
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

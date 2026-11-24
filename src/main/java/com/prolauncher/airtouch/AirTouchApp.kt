package com.prolauncher.airtouch

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

enum class AirTouchAction(val titleKo: String) {
 NONE("동작 없음 (비활성)"),
 GLOBAL_BACK("뒤로 가기 (Back)"),
 GLOBAL_HOME("홈 화면 이동 (Home)"),
 GLOBAL_RECENTS("최근 앱 목록 (Recents)"),
 GLOBAL_NOTIFICATIONS("알림창 열기"),
 GLOBAL_QUICK_SETTINGS("빠른 설정창 열기"),
 GLOBAL_LOCK_SCREEN("화면 잠금 (Lock Screen)"),
 GLOBAL_SCREENSHOT("화면 캡처 (Screenshot)"),
 DOUBLE_TAP_AT_CURSOR("커서/중앙 위치 더블 클릭"),
 ZOOM_IN_AT_CURSOR("커서/중앙 위치 화면 확대 (+)"),
 ZOOM_OUT_AT_CURSOR("커서/중앙 위치 화면 축소 (-)"),
 MEDIA_PLAY_PAUSE("미디어 재생 / 일시정지"),
 MEDIA_NEXT("다음 곡 / 영상"),
 MEDIA_PREV("이전 곡 / 영상"),
 VOLUME_UP("볼륨 높이기 (+)"),
 VOLUME_DOWN("볼륨 낮추기 (-)"),
 TOGGLE_PAUSE_TRACKING("제스처·커서 일시정지 / 재개 토글")
}
enum class HandPose(val key: String, val labelKo: String, val howToKo: String, val defaultAction: AirTouchAction) {
 PEACE("pose_v34_peace", "\u270C\uFE0F 브이 (검지+중지)", "약지·새끼는 접고 검지·중지 2개만 편 채 0.6초 정지", AirTouchAction.GLOBAL_BACK),
 THREE_FINGERS("pose_v34_three", "\u261D\uFE0F 세 손가락 (검지+중지+약지)", "엄지·새끼는 접고 검지·중지·약지 3개를 편 채 0.6초 정지", AirTouchAction.GLOBAL_RECENTS),
 THUMB_INDEX_MID("pose_v34_tim", "\u270B 삼지 포즈 (엄지+검지+중지)", "약지·새끼는 접고 엄지·검지·중지 3개를 편 채 0.6초 정지", AirTouchAction.GLOBAL_NOTIFICATIONS),
 FOUR_FINGERS("pose_v34_four", "\uD83D\uDD96 네 손가락 (엄지만 접음)", "엄지만 접고 나머지 4손가락을 편 채 제자리에서 0.6초 정지", AirTouchAction.VOLUME_UP),
 OPEN_PALM("pose_v34_palm", "\uD83D\uDD90\uFE0F 손바닥 활짝 (5개 모두 폄)", "다섯 손가락을 모두 편 채 흔들지 않고 제자리 0.6초 정지", AirTouchAction.GLOBAL_HOME),
 ROCK_ON("pose_v34_rock", "\uD83E\uDD18 락앤롤 (검지+새끼)", "엄지·중지·약지는 접고 검지·새끼만 편 채 0.6초 정지", AirTouchAction.GLOBAL_LOCK_SCREEN),
 SHAKA("pose_v34_shaka", "\uD83E\uDD19 샤카/전화 (엄지+새끼)", "검지·중지·약지는 접고 엄지·새끼만 편 채 0.6초 정지", AirTouchAction.VOLUME_DOWN),
 THUMB_UP("pose_v34_thumb", "\uD83D\uDC4D 엄지 척 (엄지만 폄)", "네 손가락을 모두 접고 엄지만 편 채 0.6초 정지", AirTouchAction.MEDIA_PLAY_PAUSE),
 PINKY_ONLY("pose_v34_pinky", "\uD83E\uDD1E 새끼손가락 약속 (새끼만 폄)", "엄지·검지·중지·약지를 접고 새끼손가락만 편 채 0.6초 정지", AirTouchAction.GLOBAL_SCREENSHOT),
 PINCH_THREE("pose_v34_p3", "\uD83E\uDD1F 스파이더맨 (엄지+검지+새끼)", "중지·약지만 접고 엄지·검지·새끼 3개를 편 채 0.6초 정지", AirTouchAction.GLOBAL_QUICK_SETTINGS),
 L_GUN("pose_v34_lgun", "\uD83D\uDC49 총 모양 L (엄지+검지 활짝)", "클릭 준비 자세와 겹치지 않도록 기본 비활성 (원할 때 동작 지정 가능)", AirTouchAction.NONE),
 FIST("pose_v34_fist", "\u270A 주먹 쥐기 (5개 모두 접음)", "다섯 손가락을 모두 말아 쥔 채 0.6초 정지", AirTouchAction.TOGGLE_PAUSE_TRACKING)
}
enum class ActiveGestureMode {
 PAUSED, PINCH_CLICK, PALM_SCROLL, SHORTCUT_POSE, POINTER_MOVE, MOTION_ONLY
}
class AirTouchConfig(context: Context) {
 private val prefs: SharedPreferences = context.getSharedPreferences("airtouch_lowram_prefs", Context.MODE_PRIVATE)
 var mousePointerEnabled: Boolean
  get() = prefs.getBoolean("mouse_pointer_v35", true)
  set(v) = prefs.edit().putBoolean("mouse_pointer_v35", v).apply()
 var pinchClickEnabled: Boolean
  get() = prefs.getBoolean("pinch_click_v35", true)
  set(v) = prefs.edit().putBoolean("pinch_click_v35", v).apply()
 var cursorSpeed: Float
  get() = prefs.getFloat("cursor_speed_v30", 1.75f)
  set(v) = prefs.edit().putFloat("cursor_speed_v30", v.coerceIn(0.6f, 3.2f)).apply()
 var pinchRatioThreshold: Float
  get() = prefs.getFloat("pinch_ratio_v34", 0.42f)
  set(v) = prefs.edit().putFloat("pinch_ratio_v34", v.coerceIn(0.24f, 0.58f)).apply()
 var thumbTriggerClickEnabled: Boolean
  get() = prefs.getBoolean("thumb_trigger_click_v34", true)
  set(v) = prefs.edit().putBoolean("thumb_trigger_click_v34", v).apply()
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
   .putBoolean("mouse_pointer_v35", true)
   .putBoolean("pinch_click_v35", true)
   .putFloat("cursor_speed_v30", 1.75f)
   .putFloat("pinch_ratio_v34", 0.42f)
   .putBoolean("thumb_trigger_click_v34", true)
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
 @Volatile var thumbSideRatio: Float = 1.0f
 @Volatile var estimatedDistanceCm: Int = 35
 @Volatile var orientationText: String = "세로 모드"
 @Volatile var cameraResolutionText: String = "대기 중"
 @Volatile var lastTriggeredActionText: String = "없음"
 @Volatile var inferenceLatencyMs: Long = 0L
 @Volatile var clickCount: Int = 0
 @Volatile var scrollCount: Int = 0
}
class StableOneEuroFilter(
 private val minCutoffHz: Float = 1.40f,
 private val beta: Float = 15.5f,
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
  val clampedX = xHat + (rawX - xHat).coerceIn(-0.25f, 0.25f)
  val clampedY = yHat + (rawY - yHat).coerceIn(-0.25f, 0.25f)
  val aDeriv = smoothingFactor(dt, dCutoffHz)
  dxHat += aDeriv * (((clampedX - xHat) / dt) - dxHat)
  dyHat += aDeriv * (((clampedY - yHat) / dt) - dyHat)
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
  val triggeredPoseAction: AirTouchAction?,
  val scrollDirection: ScrollDirection,
  val scrollStrength: Float,
  val activeFeedbackBadge: String,
  val nearFrameEdge: Boolean,
  val estimatedCm: Int,
  val landmarksXY: FloatArray,
  val modeSummary: String
 )
 private val baseJointFilter = StableOneEuroFilter(minCutoffHz = 1.35f, beta = 14.5f)
 private val tipFilter = StableOneEuroFilter(minCutoffHz = 1.45f, beta = 15.5f)
 private val palmFilter = StableOneEuroFilter(minCutoffHz = 2.20f, beta = 20.0f)
 @Volatile private var cursorX = 0.5f
 @Volatile private var cursorY = 0.5f
 private var lockedClickX = 0.5f; private var lockedClickY = 0.5f
 private var prevBaseX = 0.5f; private var prevBaseY = 0.5f
 private var prevTipX = 0.5f; private var prevTipY = 0.5f
 private var lastValidEvalMs = 0L; private var handEnteredFrameMs = 0L
 private var smoothedHandScale2D = 0.18f; private var handScaleInitialized = false
 private val fingerScore = FloatArray(5) { 0f }
 private val fingerState = BooleanArray(5) { false }
 private var openPinchBaseline = 0.78f
 private var prevPinchRatio = 1.0f
 private var prevThumbSideRatio = 1.0f
 private var pinchArmed = true
 private var thumbTriggerArmed = false
 private var isThumbTriggerHold = false
 private var isCurrentlyPinching = false
 private var pinchMinRatioInHold = 1.0f
 private var pinchStartTimeMs = 0L
 private var lastTapClickTimeMs = 0L
 private var postPinchFreezeUntilMs = 0L
 private val histSize = 16
 private val cursorHistX = FloatArray(histSize) { 0.5f }
 private val cursorHistY = FloatArray(histSize) { 0.5f }
 private val cursorHistRatio = FloatArray(histSize) { 0.8f }
 private val cursorHistTs = LongArray(histSize)
 private var cursorHistHead = 0; private var cursorHistCount = 0
 private val bufSize = 12
 private val palmBufX = FloatArray(bufSize)
 private val palmBufY = FloatArray(bufSize)
 private val pinchRatioBuf = FloatArray(bufSize)
 private val palmBufTs = LongArray(bufSize)
 private var palmBufHead = 0; private var palmBufCount = 0
 private var prevSmoothPalmX = -1f; private var prevSmoothPalmY = -1f
 private var lastScrollHandOpenMs = 0L
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
 fun recenterCursor() {
  cursorX = 0.5f; cursorY = 0.5f
  lockedClickX = 0.5f; lockedClickY = 0.5f
  cursorHistCount = 0; cursorHistHead = 0
 }
 private fun computePadPinchRatio(lm: List<NormalizedLandmark>, handScale: Float): Float {
  val dTip = dist2D(lm[4], lm[8])
  val dThumbToIdx = distPointToSegment2D(lm[4], lm[7], lm[8]) * 1.02f
  val dIdxToThumb = distPointToSegment2D(lm[8], lm[3], lm[4]) * 1.02f
  return minOf(dTip, dThumbToIdx, dIdxToThumb) / handScale
 }
 private fun computeThumbSideTriggerRatio(lm: List<NormalizedLandmark>, handScale: Float): Float {
  val dLower = distPointToSegment2D(lm[4], lm[5], lm[6]) * 1.04f
  val dUpper = distPointToSegment2D(lm[4], lm[6], lm[7])
  return minOf(dLower, dUpper) / handScale
 }
 private fun rewindCursorToPrePinchAnchor(now: Long, currentRatio: Float) {
  if (cursorHistCount < 2) {
   lockedClickX = cursorX; lockedClickY = cursorY
   return
  }
  var bestX = cursorX; var bestY = cursorY
  for (k in 1 until cursorHistCount) {
   val idx = (cursorHistHead - 1 - k + histSize * 2) % histSize
   val age = now - cursorHistTs[idx]
   if (age < 55L) continue
   if (age > 210L) break
   bestX = cursorHistX[idx]; bestY = cursorHistY[idx]
   if (cursorHistRatio[idx] >= currentRatio + 0.08f) break
  }
  if (hypot(cursorX - bestX, cursorY - bestY) < 0.065f) {
   cursorX = bestX; cursorY = bestY
  }
  lockedClickX = cursorX; lockedClickY = cursorY
 }
 fun evaluate(lm: List<NormalizedLandmark>): FrameEvaluation {
  val now = SystemClock.uptimeMillis()
  val pointerEnabled = config.mousePointerEnabled
  val clickEnabled = config.pinchClickEnabled
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
  val (smoothBaseX, smoothBaseY) = baseJointFilter.filter(
   indexMcp.x() * 0.52f + indexPip.x() * 0.48f,
   indexMcp.y() * 0.52f + indexPip.y() * 0.48f, now
  )
  val (smoothTipX, smoothTipY) = tipFilter.filter(indexTip.x(), indexTip.y(), now)
  val (palmX, palmY) = palmFilter.filter(
   wrist.x() * 0.30f + indexMcp.x() * 0.25f + middleMcp.x() * 0.25f + pinkyMcp.x() * 0.20f,
   wrist.y() * 0.30f + indexMcp.y() * 0.25f + middleMcp.y() * 0.25f + pinkyMcp.y() * 0.20f, now
  )
  updateFingerHysteresis(0, isThumbExtendedRaw(lm, handScale))
  updateFingerHysteresis(1, isFingerExtendedRaw(wrist, lm[5], lm[6], lm[7], lm[8]))
  updateFingerHysteresis(2, isFingerExtendedRaw(wrist, lm[9], lm[10], lm[11], lm[12]))
  updateFingerHysteresis(3, isFingerExtendedRaw(wrist, lm[13], lm[14], lm[15], lm[16]))
  updateFingerHysteresis(4, isFingerExtendedRaw(wrist, lm[17], lm[18], lm[19], lm[20]))
  val thumbUp = fingerState[0]; val indexUp = fingerState[1]; val middleUp = fingerState[2]; val ringUp = fingerState[3]; val pinkyUp = fingerState[4]
  AirTouchTelemetry.fingerStatesText = "엄지:${if (thumbUp) "O" else "X"} 검지:${if (indexUp) "O" else "X"} 중지:${if (middleUp) "O" else "X"} 약지:${if (ringUp) "O" else "X"} 새끼:${if (pinkyUp) "O" else "X"}"
  val padPinchRatio = computePadPinchRatio(lm, handScale)
  val thumbSideRatio = computeThumbSideTriggerRatio(lm, handScale)
  val ratioThreshold = config.pinchRatioThreshold
  val handReentered = (now - lastValidEvalMs) > 220L
  if (handReentered) {
   handEnteredFrameMs = now
   prevBaseX = smoothBaseX; prevBaseY = smoothBaseY
   prevTipX = smoothTipX; prevTipY = smoothTipY
   prevSmoothPalmX = palmX; prevSmoothPalmY = palmY
   prevPinchRatio = padPinchRatio; prevThumbSideRatio = thumbSideRatio
   isCurrentlyPinching = false; isThumbTriggerHold = false
   pinchArmed = padPinchRatio >= ratioThreshold * 1.12f
   thumbTriggerArmed = false
   palmBufCount = 0; palmBufHead = 0; cursorHistCount = 0; cursorHistHead = 0
  }
  val dtSec = ((now - lastValidEvalMs).coerceIn(8L, 100L)) / 1000f
  lastValidEvalMs = now
  val pinchClosingRate = prevPinchRatio - padPinchRatio
  val thumbSideClosingRate = prevThumbSideRatio - thumbSideRatio
  prevPinchRatio = padPinchRatio; prevThumbSideRatio = thumbSideRatio
  AirTouchTelemetry.pinchRatio = padPinchRatio; AirTouchTelemetry.thumbSideRatio = thumbSideRatio
  if (!isCurrentlyPinching && padPinchRatio > ratioThreshold * 1.22f && abs(pinchClosingRate) < 0.035f) {
   openPinchBaseline = (openPinchBaseline + (padPinchRatio.coerceIn(0.54f, 1.08f) - openPinchBaseline) * 0.08f).coerceIn(0.54f, 1.08f)
  }
  val entryThresh = if (openPinchBaseline > ratioThreshold * 1.62f) (ratioThreshold + 0.035f) else ratioThreshold
  val indexTuckedDeep = (dist2D(wrist, indexTip) < dist2D(wrist, indexMcp) * 0.90f) && (dist2D(wrist, indexDip) < dist2D(wrist, indexPip) * 0.93f)
  val thumbOnFist = ((dist2D(thumbTip, lm[10]) / handScale < 0.30f) && (dist2D(thumbTip, lm[10]) < dist2D(thumbTip, indexTip) * 0.94f)) ||
   (!clickEnabled && !thumbUp && dist2D(wrist, indexTip) < dist2D(wrist, indexPip) * 0.95f)
  val isTightFist = !middleUp && !ringUp && !pinkyUp && indexTuckedDeep && thumbOnFist
  val isPureIndexPointer = indexUp && !middleUp && !ringUp && !pinkyUp
  if (!clickEnabled || !isPureIndexPointer) thumbTriggerArmed = false
  else if (!isCurrentlyPinching && thumbSideRatio >= 0.43f) thumbTriggerArmed = true
  if (!isCurrentlyPinching && padPinchRatio >= entryThresh * 1.14f) {
   pinchArmed = true
  }
  val instStepX = if (prevSmoothPalmX >= 0f) (palmX - prevSmoothPalmX) else 0f
  val instStepY = if (prevSmoothPalmY >= 0f) (palmY - prevSmoothPalmY) else 0f
  val palmSpeedNorm = (hypot(instStepX, instStepY) / dtSec) * distGain
  prevSmoothPalmX = palmX; prevSmoothPalmY = palmY
  palmBufX[palmBufHead] = palmX; palmBufY[palmBufHead] = palmY
  pinchRatioBuf[palmBufHead] = padPinchRatio; palmBufTs[palmBufHead] = now
  palmBufHead = (palmBufHead + 1) % bufSize
  if (palmBufCount < bufSize) palmBufCount++
  var tapClicked = false
  if (clickEnabled && !isTrackingPaused && !isTightFist) {
   val padHit = pinchArmed && (padPinchRatio < entryThresh)
   val triggerHit = config.thumbTriggerClickEnabled && isPureIndexPointer && thumbTriggerArmed &&
    (thumbSideRatio < 0.32f) && (thumbSideClosingRate > 0.014f)
   if (!isCurrentlyPinching) {
    if ((padHit || triggerHit) && (now - handEnteredFrameMs) > 120L && (now - lastTapClickTimeMs) > 165L) {
     isCurrentlyPinching = true
     isThumbTriggerHold = !padHit && triggerHit
     pinchArmed = false
     if (isThumbTriggerHold) thumbTriggerArmed = false
     pinchMinRatioInHold = if (isThumbTriggerHold) thumbSideRatio else padPinchRatio
     pinchStartTimeMs = now
     if (pointerEnabled) rewindCursorToPrePinchAnchor(now, padPinchRatio) else { lockedClickX = 0.5f; lockedClickY = 0.5f }
     tapClicked = true
     lastTapClickTimeMs = now
     postPinchFreezeUntilMs = now + 220L
     palmBufCount = 0; palmBufHead = 0
     currentCandidatePose = null; poseTriggeredInHold = false
     AirTouchTelemetry.clickCount++
     feedbackBadgeLabel = if (isThumbTriggerHold) "\uD83D\uDC4C 방아쇠 클릭!" else "\uD83D\uDC4C 클릭!"
     feedbackBadgeUntilMs = now + 380L
     AirTouchTelemetry.lastTriggeredActionText = if (isThumbTriggerHold) "\uD83D\uDC4C 엄지 방아쇠 클릭" else "\uD83D\uDC4C 단일 클릭 (Tap)"
    }
   } else {
    val activeRatio = if (isThumbTriggerHold) thumbSideRatio else padPinchRatio
    pinchMinRatioInHold = minOf(pinchMinRatioInHold, activeRatio)
    if (pointerEnabled && (now - pinchStartTimeMs) < 220L) {
     cursorX = lockedClickX; cursorY = lockedClickY
    }
    val reopened = if (isThumbTriggerHold) {
     activeRatio >= 0.39f || (activeRatio >= pinchMinRatioInHold + 0.075f)
    } else {
     (padPinchRatio >= entryThresh * 1.14f) ||
      ((now - pinchStartTimeMs) >= 35L && padPinchRatio >= pinchMinRatioInHold + 0.085f && padPinchRatio >= entryThresh * 1.03f)
    }
    if (reopened) {
     isCurrentlyPinching = false
     isThumbTriggerHold = false
     pinchArmed = true
     postPinchFreezeUntilMs = maxOf(postPinchFreezeUntilMs, now + 130L)
    }
   }
  } else {
   isCurrentlyPinching = false
   isThumbTriggerHold = false
   if (!clickEnabled) postPinchFreezeUntilMs = 0L
  }
  if (clickEnabled && !isTrackingPaused && !isCurrentlyPinching && !tapClicked && !isTightFist && pinchArmed &&
   (now - lastTapClickTimeMs) > 210L && (now - lastSwipeTriggerTimeMs) > 340L &&
   (now - handEnteredFrameMs) > 160L && palmSpeedNorm < 0.32f && palmBufCount >= 3
  ) {
   var valleyMin = 9f; var valleyIdxOffset = -1
   for (k in 1 until minOf(palmBufCount, 7)) {
    val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
    if ((now - palmBufTs[idx]) > 280L) break
    val r = pinchRatioBuf[idx]
    if (r < valleyMin) { valleyMin = r; valleyIdxOffset = k }
   }
   if (valleyIdxOffset >= 1 && valleyMin < entryThresh * 1.15f && (padPinchRatio - valleyMin) >= 0.055f) {
    var prePeak = valleyMin
    for (k in (valleyIdxOffset + 1) until palmBufCount) {
     val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
     if ((now - palmBufTs[idx]) > 400L) break
     val r = pinchRatioBuf[idx]
     if (r > prePeak) prePeak = r
    }
    if ((prePeak - valleyMin) >= 0.130f && prePeak >= entryThresh * 1.20f) {
     if (pointerEnabled) rewindCursorToPrePinchAnchor(now, valleyMin) else { lockedClickX = 0.5f; lockedClickY = 0.5f }
     tapClicked = true
     lastTapClickTimeMs = now
     postPinchFreezeUntilMs = now + 200L
     palmBufCount = 0; palmBufHead = 0
     AirTouchTelemetry.clickCount++
     feedbackBadgeLabel = "\uD83D\uDC4C 클릭!"
     feedbackBadgeUntilMs = now + 380L
     AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDC4C 소프트 탭 클릭"
    }
   }
  }
  val uncurlingAfterClick = clickEnabled && ((now < postPinchFreezeUntilMs) || ((now - lastTapClickTimeMs) < 280L && pinchClosingRate < -0.010f))
  val pinchFreeze = clickEnabled && (
   isCurrentlyPinching || tapClicked || uncurlingAfterClick ||
   (pinchArmed && padPinchRatio < entryThresh * 1.16f) ||
   (pinchArmed && pinchClosingRate > 0.011f && padPinchRatio < entryThresh * 1.48f) ||
   (config.thumbTriggerClickEnabled && isPureIndexPointer && thumbTriggerArmed && thumbSideClosingRate > 0.018f && thumbSideRatio < 0.40f)
  )
  val isOpenPalmForScroll = indexUp && middleUp && ringUp && !isCurrentlyPinching && (!clickEnabled || padPinchRatio > entryThresh * 1.28f)
  if (isOpenPalmForScroll) lastScrollHandOpenMs = now
  var scrollDir = ScrollDirection.NONE
  var scrollStrength = 1.0f
  if (config.swipeScrollEnabled && !isTrackingPaused && !isCurrentlyPinching && !pinchFreeze && !tapClicked &&
   (now - handEnteredFrameMs) > 150L && (!clickEnabled || (now - lastTapClickTimeMs) > 280L) &&
   (now - lastScrollHandOpenMs) <= 250L && (now - lastSwipeTriggerTimeMs) > 280L && palmBufCount >= 2
  ) {
   var bestDx = 0f; var bestDy = 0f; var bestMag = 0f; var bestDtMs = 100L
   for (k in 1 until palmBufCount) {
    val idx = (palmBufHead - 1 - k + bufSize * 2) % bufSize
    val dtWin = now - palmBufTs[idx]
    if (dtWin < 40L) continue
    if (dtWin > 290L) break
    val dx = (palmX - palmBufX[idx]) * distGain; val dy = (palmY - palmBufY[idx]) * distGain
    val mag = hypot(dx, dy)
    if (mag > bestMag) { bestMag = mag; bestDx = dx; bestDy = dy; bestDtMs = dtWin }
   }
   val baseThresh = config.palmSwipeThreshold
   val avgSpeed = bestMag / (bestDtMs.coerceAtLeast(40L) / 1000f)
   if (bestMag >= baseThresh && avgSpeed >= 0.32f) {
    var cand = ScrollDirection.NONE; var domDisp = 0f
    if (abs(bestDy) >= baseThresh && abs(bestDy) > abs(bestDx) * 1.15f && (bestDy * instStepY >= -0.002f)) {
     val moveUp = bestDy < 0f
     cand = if (!config.invertPalmScroll) (if (moveUp) ScrollDirection.DOWN else ScrollDirection.UP) else (if (moveUp) ScrollDirection.UP else ScrollDirection.DOWN)
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
      scrollDir = cand; scrollStrength = (domDisp / baseThresh).coerceIn(1.0f, 1.85f)
      lastSwipeTriggerTimeMs = now; lastScrollDir = cand
      palmBufCount = 0; palmBufHead = 0; currentCandidatePose = null; poseTriggeredInHold = false
      AirTouchTelemetry.scrollCount++
      feedbackBadgeLabel = when (cand) {
       ScrollDirection.DOWN -> "▼ 아래 스크롤"; ScrollDirection.UP -> "▲ 위로 스크롤"
       ScrollDirection.LEFT -> "◀ 왼쪽 스와이프"; ScrollDirection.RIGHT -> "▶ 오른쪽 스와이프"
       else -> ""
      }
      feedbackBadgeUntilMs = now + 520L
      AirTouchTelemetry.lastTriggeredActionText = "\uD83D\uDD90\uFE0F $feedbackBadgeLabel"
     }
    }
   }
  }
  val isSingleIndexPointing = pointerEnabled && !thumbUp && indexUp && !middleUp && !ringUp && !pinkyUp
  val matchedPose = if (isCurrentlyPinching || pinchFreeze || tapClicked || isSingleIndexPointing ||
   (clickEnabled && (now - lastTapClickTimeMs) < 450L) || (now - lastSwipeTriggerTimeMs) < 850L
  ) null else when {
   isTightFist && !thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp -> HandPose.FIST
   isTrackingPaused -> null
   !thumbUp && indexUp && middleUp && !ringUp && !pinkyUp -> HandPose.PEACE
   thumbUp && indexUp && !middleUp && !ringUp && !pinkyUp && (!clickEnabled || padPinchRatio > maxOf(entryThresh * 1.75f, 0.76f)) -> HandPose.L_GUN
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
  val rawPose = matchedPose?.takeIf { config.getActionForPose(it) != AirTouchAction.NONE }
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
      isTrackingPaused = !isTrackingPaused; triggeredPoseAction = mapped
      AirTouchTelemetry.lastTriggeredActionText = if (isTrackingPaused) "일시정지 켜짐" else "일시정지 해제됨"
     } else if (!isTrackingPaused) {
      triggeredPoseAction = mapped
      AirTouchTelemetry.lastTriggeredActionText = "${rawPose.labelKo} -> ${mapped.titleKo}"
     }
    }
   }
  } else if (palmSpeedNorm >= 0.15f || rawPose == null) {
   currentCandidatePose = null; poseTriggeredInHold = false
  }
  val inPalmScrollMotion = config.swipeScrollEnabled && isOpenPalmForScroll && palmSpeedNorm >= 0.16f
  val freezeForPoseHold = poseProgress > 0.20f
  if (!pointerEnabled) {
   cursorX = 0.5f; cursorY = 0.5f
   lockedClickX = 0.5f; lockedClickY = 0.5f
   cursorHistCount = 0; cursorHistHead = 0
  } else if (!isTrackingPaused && !inPalmScrollMotion && !handReentered && !pinchFreeze && !freezeForPoseHold && (now - lastSwipeTriggerTimeMs) > 220L) {
   val dBaseX = smoothBaseX - prevBaseX; val dBaseY = smoothBaseY - prevBaseY
   val dTipX = smoothTipX - prevTipX; val dTipY = smoothTipY - prevTipY
   val tipWeight = if (clickEnabled && padPinchRatio < entryThresh * 1.55f) {
    (((padPinchRatio - entryThresh * 1.22f) / (entryThresh * 0.33f)).coerceIn(0f, 1f)) * 0.22f
   } else 0.22f
   val rawDx = dBaseX * (1f - tipWeight) + dTipX * tipWeight
   val rawDy = dBaseY * (1f - tipWeight) + dTipY * tipWeight
   val mag = hypot(rawDx, rawDy)
   val softKnee = mag / (mag + 0.00095f)
   val normVel = (mag / dtSec) * distGain
   val accel = if (tipWeight > 0.12f) 1.0f + (normVel * 0.55f).coerceAtMost(1.35f) else 0.65f
   val gain = config.cursorSpeed * 1.92f * distGain * softKnee * accel * (if (nearEdge) 0.35f else 1.0f)
   cursorX = (cursorX + rawDx * gain).coerceIn(0f, 1f)
   cursorY = (cursorY + rawDy * gain * 1.08f).coerceIn(0f, 1f)
   cursorHistX[cursorHistHead] = cursorX; cursorHistY[cursorHistHead] = cursorY
   cursorHistRatio[cursorHistHead] = padPinchRatio; cursorHistTs[cursorHistHead] = now
   cursorHistHead = (cursorHistHead + 1) % histSize
   if (cursorHistCount < histSize) cursorHistCount++
  }
  prevBaseX = smoothBaseX; prevBaseY = smoothBaseY
  prevTipX = smoothTipX; prevTipY = smoothTipY
  val activeBadge = if (now < feedbackBadgeUntilMs) feedbackBadgeLabel else ""
  val isClickFlash = activeBadge.startsWith("\uD83D\uDC4C")
  val mode = when {
   isTrackingPaused -> ActiveGestureMode.PAUSED
   clickEnabled && (isCurrentlyPinching || isClickFlash) -> ActiveGestureMode.PINCH_CLICK
   inPalmScrollMotion || (activeBadge.isNotEmpty() && !isClickFlash) -> ActiveGestureMode.PALM_SCROLL
   poseProgress > 0.15f && currentCandidatePose != null -> ActiveGestureMode.SHORTCUT_POSE
   !pointerEnabled -> ActiveGestureMode.MOTION_ONLY
   else -> ActiveGestureMode.POINTER_MOVE
  }
  val summary = when (mode) {
   ActiveGestureMode.PAUSED -> "[일시정지] \u270A 주먹 0.6초 쥐어 재개"
   ActiveGestureMode.PINCH_CLICK -> if (activeBadge.isNotEmpty()) activeBadge else "\uD83D\uDC4C 클릭 완료 (손가락을 펴면 다음 클릭 준비)"
   ActiveGestureMode.PALM_SCROLL -> if (activeBadge.isNotEmpty()) "\uD83D\uDD90\uFE0F $activeBadge" else "\uD83D\uDD90\uFE0F 손바닥 스크롤 중 (${estCm}cm)"
   ActiveGestureMode.SHORTCUT_POSE -> "${currentCandidatePose!!.labelKo} (${(poseProgress * 100).toInt()}%)"
   ActiveGestureMode.MOTION_ONLY -> when {
    nearEdge -> "손을 카메라 안쪽으로 옮겨주세요 (${estCm}cm)"
    !clickEnabled && config.swipeScrollEnabled -> "\uD83D\uDD90\uFE0F 손가락 모션 전용 (${estCm}cm · 스크롤 / 12종 포즈)"
    clickEnabled && config.swipeScrollEnabled -> "\uD83D\uDD90\uFE0F 커서 숨김 모션 (${estCm}cm · 클릭 / 스크롤 / 포즈)"
    clickEnabled && !config.swipeScrollEnabled -> "\uD83D\uDC4C 커서 숨김 클릭·포즈 (${estCm}cm)"
    else -> "\u270C\uFE0F 12종 손가락 포즈 전용 (${estCm}cm)"
   }
   ActiveGestureMode.POINTER_MOVE -> when {
    nearEdge -> "손을 카메라 안쪽으로 옮겨주세요 (${estCm}cm)"
    clickEnabled && config.swipeScrollEnabled -> "\u261D\uFE0F 에어마우스 (${estCm}cm · 맞대면 클릭 / \uD83D\uDD90\uFE0F스크롤)"
    !clickEnabled && config.swipeScrollEnabled -> "\u261D\uFE0F 포인터+모션 (${estCm}cm · 클릭 OFF / \uD83D\uDD90\uFE0F스크롤)"
    clickEnabled && !config.swipeScrollEnabled -> "\u261D\uFE0F 포인터+클릭 (${estCm}cm · 맞대면 즉시 클릭)"
    else -> "\u261D\uFE0F 포인터 이동 전용 (${estCm}cm · 클릭·스크롤 OFF)"
   }
  }
  AirTouchTelemetry.currentModeText = summary
  return FrameEvaluation(
   mode = mode,
   normCursorX = cursorX,
   normCursorY = cursorY,
   isPinching = isCurrentlyPinching,
   gaugeProgress = poseProgress,
   tapClicked = tapClicked,
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
  return ((v1x * v2x + v1y * v2y) / (hypot(v1x, v1y).coerceAtLeast(1e-4f) * hypot(v2x, v2y).coerceAtLeast(1e-4f))) > 0.35f
 }
 private fun isThumbExtendedRaw(lm: List<NormalizedLandmark>, handScale: Float): Boolean {
  val wrist = lm[0]; val cmc = lm[1]; val mcp = lm[2]; val ip = lm[3]; val tip = lm[4]
  val v1x = ip.x() - cmc.x(); val v1y = ip.y() - cmc.y(); val v2x = tip.x() - mcp.x(); val v2y = tip.y() - mcp.y()
  val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y).coerceAtLeast(1e-4f) * hypot(v2x, v2y).coerceAtLeast(1e-4f))
  return (dist2D(tip, lm[5]) / handScale > 0.46f) && (dist2D(tip, lm[9]) / handScale > 0.55f) && (dist2D(wrist, tip) > dist2D(wrist, mcp) * 1.08f) && cos > 0.38f
 }
 private fun dist2D(a: NormalizedLandmark, b: NormalizedLandmark): Float = hypot(a.x() - b.x(), a.y() - b.y())
 private fun distPointToSegment2D(p: NormalizedLandmark, a: NormalizedLandmark, b: NormalizedLandmark): Float {
  val abx = b.x() - a.x(); val aby = b.y() - a.y()
  val lenSq = abx * abx + aby * aby
  if (lenSq < 1e-6f) return dist2D(p, a)
  val t = (((p.x() - a.x()) * abx + (p.y() - a.y()) * aby) / lenSq).coerceIn(0f, 1f)
  return hypot(p.x() - (a.x() + t * abx), p.y() - (a.y() + t * aby))
 }
}

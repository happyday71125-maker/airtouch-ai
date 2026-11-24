package com.prolauncher.airtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.media.AudioManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Size
import android.view.*
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
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
 private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.0f; color = Color.parseColor("#AAFFFFFF") }
 private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
 private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4.5f; color = Color.parseColor("#FFD740") }
 private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00B0FF") }
 private val pillProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * density; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#40C4FF") }
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
 private val winOffset = IntArray(2)
 var targetX = 540f; var targetY = 960f; var renderX = 540f; var renderY = 960f
 private var rippleGlobalX = 0f; private var rippleGlobalY = 0f; private var rippleStartMs = 0L
 private var lastDrawMs = 0L
 var currentMode = ActiveGestureMode.POINTER_MOVE
 var handVisible = false; var nearEdge = false; var estimatedCm = 35
 var gaugeProgress = 0f; var statusLabel = "AirTouch 대기 중"; var feedbackBadge = ""
 var showPointerCursor = true; var showStatusPill = true; var showSkeletonMini = true
 fun triggerClickRipple(globalX: Float, globalY: Float) {
  rippleGlobalX = globalX; rippleGlobalY = globalY; rippleStartMs = SystemClock.uptimeMillis(); postInvalidateOnAnimation()
 }
 fun updateState(
  x: Float, y: Float, mode: ActiveGestureMode, handFound: Boolean, edgeWarn: Boolean, estCm: Int,
  landmarks: FloatArray?, progress: Float, label: String, badge: String,
  showCursor: Boolean, showPill: Boolean, showMini: Boolean
 ) {
  targetX = x; targetY = y; currentMode = mode; handVisible = handFound; nearEdge = edgeWarn; estimatedCm = estCm
  if (landmarks != null && landmarks.size >= 42) System.arraycopy(landmarks, 0, lmPts, 0, 42)
  gaugeProgress = progress; statusLabel = label; feedbackBadge = badge
  showPointerCursor = showCursor; showStatusPill = showPill; showSkeletonMini = showMini
  if (!showCursor || mode == ActiveGestureMode.PINCH_CLICK || hypot(targetX - renderX, targetY - renderY) > 360f) {
   renderX = targetX; renderY = targetY
  }
  postInvalidateOnAnimation()
 }
 override fun onDraw(canvas: Canvas) {
  super.onDraw(canvas)
  getLocationOnScreen(winOffset)
  val now = SystemClock.uptimeMillis()
  val dt = ((now - lastDrawMs).coerceIn(4L, 40L)) / 1000f
  lastDrawMs = now
  val themeColor = when (currentMode) {
   ActiveGestureMode.PAUSED -> Color.parseColor("#FF5252")
   ActiveGestureMode.PINCH_CLICK -> Color.parseColor("#FFD740")
   ActiveGestureMode.PALM_SCROLL -> Color.parseColor("#00E5FF")
   ActiveGestureMode.SHORTCUT_POSE -> Color.parseColor("#40C4FF")
   ActiveGestureMode.MOTION_ONLY -> Color.parseColor("#B388FF")
   ActiveGestureMode.POINTER_MOVE -> Color.parseColor("#00E676")
  }
  val rippleElapsed = now - rippleStartMs
  if (rippleElapsed in 0L..320L) {
   val t = rippleElapsed / 320f
   ripplePaint.alpha = ((1f - t) * 235f).toInt().coerceIn(0, 255)
   canvas.drawCircle(rippleGlobalX - winOffset[0], rippleGlobalY - winOffset[1], (14f + 34f * t) * (density * 0.45f).coerceAtLeast(1f), ripplePaint)
   postInvalidateOnAnimation()
  }
  if (showPointerCursor) {
   val dx = targetX - renderX; val dy = targetY - renderY
   if (currentMode == ActiveGestureMode.PINCH_CLICK || hypot(dx, dy) < 0.35f) {
    renderX = targetX; renderY = targetY
   } else {
    val blend = (1.0f - exp(-38.0f * dt)).coerceIn(0.20f, 0.88f)
    renderX += dx * blend; renderY += dy * blend
    postInvalidateOnAnimation()
   }
   val drawX = renderX - winOffset[0]; val drawY = renderY - winOffset[1]
   val isPinch = currentMode == ActiveGestureMode.PINCH_CLICK
   val isPalm = currentMode == ActiveGestureMode.PALM_SCROLL
   val r = if (isPinch) 19f else 26f
   outerRingPaint.color = themeColor
   canvas.drawCircle(drawX, drawY, r, cursorShadowPaint)
   canvas.drawCircle(drawX, drawY, r, outerRingPaint)
   if (isPalm) {
    val arm = 9f * density
    canvas.drawLine(drawX - arm, drawY, drawX + arm, drawY, outerRingPaint)
    canvas.drawLine(drawX, drawY - arm, drawX, drawY + arm, outerRingPaint)
   } else {
    val ch = 5.5f * density
    canvas.drawLine(drawX - ch, drawY, drawX + ch, drawY, crosshairPaint)
    canvas.drawLine(drawX, drawY - ch, drawX, drawY + ch, crosshairPaint)
    centerDotPaint.color = if (isPinch) Color.parseColor("#FFD740") else Color.WHITE
    canvas.drawCircle(drawX, drawY, if (isPinch) 9.5f else 4.8f, centerDotPaint)
   }
   if (gaugeProgress > 0.04f) {
    arcRect.set(drawX - r - 11f, drawY - r - 11f, drawX + r + 11f, drawY + r + 11f)
    canvas.drawArc(arcRect, -90f, 360f * gaugeProgress, false, progressArcPaint)
   }
   val tagText = if (feedbackBadge.isNotEmpty()) feedbackBadge else if (isPalm) "스크롤 모드" else ""
   if (tagText.isNotEmpty()) {
    cursorBadgePaint.color = if (tagText.startsWith("\uD83D\uDC4C")) Color.parseColor("#FFD740") else Color.parseColor("#00E5FF")
    val tw = cursorBadgePaint.measureText(tagText)
    val bx = (drawX - tw * 0.5f - 10f * density).coerceIn(8f, (width - tw - 28f * density).coerceAtLeast(8f))
    val by = (drawY - r - 34f * density).coerceAtLeast(12f * density)
    scrollTagRect.set(bx, by, bx + tw + 20f * density, by + 24f * density)
    canvas.drawRoundRect(scrollTagRect, 12f * density, 12f * density, pillBgPaint)
    canvas.drawText(tagText, bx + 10f * density, by + 17f * density, cursorBadgePaint)
   }
  }
  if (showStatusPill && statusLabel.isNotEmpty()) {
   val tw = labelPaint.measureText(statusLabel); val pw = tw + 36f * density
   val pl = ((width - pw) * 0.5f).coerceAtLeast(12f * density)
   val topY = (44f * density - winOffset[1]).coerceAtLeast(10f * density)
   pillRect.set(pl, topY, pl + pw, topY + 30f * density)
   canvas.drawRoundRect(pillRect, 16f * density, 16f * density, pillBgPaint)
   canvas.drawText(statusLabel, pl + 18f * density, topY + 20f * density, labelPaint)
   if (!showPointerCursor && gaugeProgress > 0.04f) {
    val barStart = pl + 14f * density
    val barEnd = barStart + (pw - 28f * density) * gaugeProgress.coerceIn(0f, 1f)
    val barY = topY + 27.5f * density
    canvas.drawLine(barStart, barY, barEnd, barY, pillProgressPaint)
   }
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
   canvas.drawText(msg, left + (boxW - miniTextPaint.measureText(msg)) * 0.5f, top + boxH * 0.52f, miniTextPaint)
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
   val isKey = (j == 4 || j == 6 || j == 7 || j == 8 || j == 5 || j == 0)
   canvas.drawCircle(left + pad + lmPts[j * 2].coerceIn(0f, 1f) * drawW, top + pad + lmPts[j * 2 + 1].coerceIn(0f, 1f) * drawH, (if (isKey) 3.4f else 2.1f) * density, if (isKey) keyJointPaint else jointPaint)
  }
  val modeTag = when (currentMode) {
   ActiveGestureMode.PALM_SCROLL -> "스크롤 · ${estimatedCm}cm"
   ActiveGestureMode.PINCH_CLICK -> "클릭 · ${estimatedCm}cm"
   ActiveGestureMode.SHORTCUT_POSE -> "포즈 ${(gaugeProgress * 100).toInt()}%"
   ActiveGestureMode.MOTION_ONLY -> "모션전용 · ${estimatedCm}cm"
   ActiveGestureMode.PAUSED -> "일시정지"
   ActiveGestureMode.POINTER_MOVE -> "포인터 · ${estimatedCm}cm"
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
  val dm = DisplayMetrics()
  @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
   val b = wm.currentWindowMetrics.bounds
   screenWidth = maxOf(b.width(), dm.widthPixels).coerceAtLeast(200)
   screenHeight = maxOf(b.height(), dm.heightPixels).coerceAtLeast(200)
  } else {
   screenWidth = dm.widthPixels.coerceAtLeast(200); screenHeight = dm.heightPixels.coerceAtLeast(200)
  }
  AirTouchTelemetry.orientationText = if (screenWidth > screenHeight) "가로 (${screenWidth}x${screenHeight})" else "세로 (${screenWidth}x${screenHeight})"
  cursorView?.let {
   it.targetX = it.targetX.coerceIn(0f, screenWidth.toFloat())
   it.targetY = it.targetY.coerceIn(0f, screenHeight.toFloat())
   it.renderX = it.renderX.coerceIn(0f, screenWidth.toFloat())
   it.renderY = it.renderY.coerceIn(0f, screenHeight.toFloat())
   it.postInvalidateOnAnimation()
  }
 }
 fun showCursorOverlay() = mainHandler.post {
  if (cursorView != null) return@post
  val wm = windowManager ?: return@post
  updateScreenMetrics()
  val ptrOn = config.mousePointerEnabled
  val view = CursorOverlayView(this).apply {
   targetX = screenWidth * 0.5f; targetY = screenHeight * 0.5f; renderX = targetX; renderY = targetY
   currentMode = if (ptrOn) ActiveGestureMode.POINTER_MOVE else ActiveGestureMode.MOTION_ONLY
   showPointerCursor = ptrOn
   showStatusPill = config.showOverlayStatusPill; showSkeletonMini = config.showSkeletonMiniView
  }
  val params = WindowManager.LayoutParams(
   WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
   WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
   PixelFormat.TRANSLUCENT
  ).apply {
   gravity = Gravity.TOP or Gravity.START
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
   else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
  }
  runCatching { wm.addView(view, params); cursorView = view }
 }
 fun refreshOverlayVisibility() = mainHandler.post {
  cursorView?.let {
   val ptrOn = config.mousePointerEnabled
   it.showPointerCursor = ptrOn
   if (!ptrOn) {
    it.targetX = screenWidth * 0.5f; it.targetY = screenHeight * 0.5f
    it.renderX = it.targetX; it.renderY = it.targetY
    if (it.currentMode == ActiveGestureMode.POINTER_MOVE) it.currentMode = ActiveGestureMode.MOTION_ONLY
   } else if (it.currentMode == ActiveGestureMode.MOTION_ONLY) {
    it.currentMode = ActiveGestureMode.POINTER_MOVE
   }
   it.showStatusPill = config.showOverlayStatusPill
   it.showSkeletonMini = config.showSkeletonMiniView
   it.postInvalidateOnAnimation()
  }
 }
 fun centerCursorOverlay() = mainHandler.post {
  updateScreenMetrics()
  cursorView?.let { it.targetX = screenWidth * 0.5f; it.targetY = screenHeight * 0.5f; it.renderX = it.targetX; it.renderY = it.targetY; it.postInvalidateOnAnimation() }
 }
 fun hideCursorOverlay() = mainHandler.post { val view = cursorView ?: return@post; runCatching { windowManager?.removeView(view) }; cursorView = null }
 fun notifyNoHandDetected(isPaused: Boolean) = mainHandler.post {
  val ptrOn = config.mousePointerEnabled
  val mode = when {
   isPaused -> ActiveGestureMode.PAUSED
   ptrOn -> ActiveGestureMode.POINTER_MOVE
   else -> ActiveGestureMode.MOTION_ONLY
  }
  val msg = when {
   isPaused -> "[일시정지] \u270A 주먹 0.6초 쥐어 재개"
   ptrOn -> "전면 카메라 시야에 손을 보여주세요 (원거리 자동 부스트 탐색 중)"
   else -> "[손가락 모션 모드] 카메라 시야에 손을 보여주세요 (원거리 탐색 중)"
  }
  cursorView?.let {
   it.updateState(
    it.targetX, it.targetY, mode, false, false,
    AirTouchTelemetry.estimatedDistanceCm, null, 0f, msg, "",
    ptrOn, config.showOverlayStatusPill, config.showSkeletonMiniView
   )
  }
 }
 fun updateCursorFromEvaluation(eval: GestureClassifier.FrameEvaluation) = mainHandler.post {
  if (cursorView == null) showCursorOverlay()
  val ptrOn = config.mousePointerEnabled
  val px = (eval.normCursorX * screenWidth).coerceIn(0f, screenWidth.toFloat())
  val py = (eval.normCursorY * screenHeight).coerceIn(0f, screenHeight.toFloat())
  cursorView?.updateState(
   px, py, eval.mode, true, eval.nearFrameEdge, eval.estimatedCm,
   eval.landmarksXY, eval.gaugeProgress, eval.modeSummary, eval.activeFeedbackBadge,
   ptrOn, config.showOverlayStatusPill, config.showSkeletonMiniView
  )
  when {
   eval.tapClicked -> { cursorView?.triggerClickRipple(px, py); dispatchTap(px, py, 65L, true) }
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
 private fun dispatchPinchZoom(cx: Float, cy: Float, zoomIn: Boolean) {
  val minDim = minOf(screenWidth, screenHeight).toFloat()
  val safeCx = cx.coerceIn(screenWidth * 0.22f, screenWidth * 0.78f)
  val safeCy = cy.coerceIn(screenHeight * 0.22f, screenHeight * 0.78f)
  val innerDist = (minDim * 0.055f).coerceAtLeast(28f)
  val outerDist = (minDim * 0.22f).coerceAtLeast(innerDist + 55f)
  val sOff = if (zoomIn) innerDist else outerDist; val eOff = if (zoomIn) outerDist else innerDist
  val c45 = 0.7071f
  val p1 = Path().apply { moveTo(safeCx - sOff * c45, safeCy - sOff * c45); lineTo(safeCx - eOff * c45, safeCy - eOff * c45) }
  val p2 = Path().apply { moveTo(safeCx + sOff * c45, safeCy + sOff * c45); lineTo(safeCx + eOff * c45, safeCy + eOff * c45) }
  val dur = 240L
  val desc = GestureDescription.Builder()
   .addStroke(GestureDescription.StrokeDescription(p1, 0L, dur))
   .addStroke(GestureDescription.StrokeDescription(p2, 0L, dur)).build()
  safeDispatchGesture(desc, dur, false)
 }
 private fun performShortcutAction(action: AirTouchAction, x: Float, y: Float) {
  when (action) {
   AirTouchAction.NONE, AirTouchAction.TOGGLE_PAUSE_TRACKING -> Unit
   AirTouchAction.ZOOM_IN_AT_CURSOR -> dispatchPinchZoom(x, y, true)
   AirTouchAction.ZOOM_OUT_AT_CURSOR -> dispatchPinchZoom(x, y, false)
   AirTouchAction.DOUBLE_TAP_AT_CURSOR -> {
    cursorView?.triggerClickRipple(x, y); dispatchTap(x, y, 52L, true)
    mainHandler.postDelayed({ cursorView?.triggerClickRipple(x, y); dispatchTap(x, y, 52L, true) }, 115L)
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
 private fun safeDispatchGesture(desc: GestureDescription, durationMs: Long, retryOnceIfBusy: Boolean = false) {
  val now = SystemClock.uptimeMillis()
  if (now < gestureBusyUntilMs) {
   val waitMs = (gestureBusyUntilMs - now + 10L)
   if (retryOnceIfBusy && waitMs <= 160L) mainHandler.postDelayed({ safeDispatchGesture(desc, durationMs, false) }, waitMs.coerceAtLeast(12L))
   return
  }
  gestureBusyUntilMs = now + durationMs + 14L
  val dispatched = dispatchGesture(desc, object : GestureResultCallback() {
   override fun onCompleted(gestureDescription: GestureDescription?) { gestureBusyUntilMs = 0L }
   override fun onCancelled(gestureDescription: GestureDescription?) { gestureBusyUntilMs = 0L }
  }, mainHandler)
  if (!dispatched) gestureBusyUntilMs = 0L
 }
 private fun dispatchTap(x: Float, y: Float, durationMs: Long, retryIfBusy: Boolean = true) {
  val tx = x.coerceIn(2f, (screenWidth - 2).coerceAtLeast(2).toFloat())
  val ty = y.coerceIn(2f, (screenHeight - 2).coerceAtLeast(2).toFloat())
  val p = Path().apply { moveTo(tx, ty); lineTo(tx, ty) }
  safeDispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), durationMs, retryIfBusy)
 }
 private fun dispatchSwipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
  val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
  safeDispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, durationMs)).build(), durationMs, false)
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
 private var cachedDisplayRotation = -1; private var farSearchToggle = false
 @Volatile private var cameraBound = false
 @Volatile private var lastFrameProcessTimeMs = 0L
 @Volatile private var lastHandDetectedTimeMs = 0L
 @Volatile private var lastSubmittedTimestampMs = 0L
 @Volatile private var inferenceBusy = false
 @Volatile private var lastInferenceStartMs = 0L
 private val screenStateReceiver = object : BroadcastReceiver() {
  override fun onReceive(context: Context?, intent: Intent?) {
   when (intent?.action) {
    Intent.ACTION_SCREEN_OFF -> { cameraBound = false; runCatching { cameraProvider?.unbindAll() } }
    Intent.ACTION_SCREEN_ON -> {
     val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
     if (isRunning && !cameraBound && km?.isKeyguardLocked != true) cameraProvider?.let { bindAnalysisUseCase(it) }
    }
    Intent.ACTION_USER_PRESENT -> if (isRunning && !cameraBound) cameraProvider?.let { bindAnalysisUseCase(it) }
   }
  }
 }
 override fun onCreate() {
  super.onCreate()
  config = AirTouchConfig(this); classifier = GestureClassifier(config)
  cameraExecutor = Executors.newSingleThreadExecutor()
  val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
  ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
 }
 override fun onConfigurationChanged(newConfig: Configuration) {
  super.onConfigurationChanged(newConfig)
  val rot = getCurrentDisplayRotation()
  if (rot != cachedDisplayRotation) { cachedDisplayRotation = rot; runCatching { imageAnalysisUseCase?.targetRotation = rot } }
 }
 private fun getCurrentDisplayRotation(): Int = runCatching {
  @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
 }.getOrDefault(Surface.ROTATION_0)
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  super.onStartCommand(intent, flags, startId)
  if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
  if (intent?.action == ACTION_RECENTER) { classifier.recenterCursor(); AirTouchAccessibilityService.instance?.centerCursorOverlay(); return START_STICKY }
  val notif = buildNotification()
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
  else startForeground(NOTIFICATION_ID, notif)
  isRunning = true; lastHandDetectedTimeMs = SystemClock.uptimeMillis()
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
     .setMinHandDetectionConfidence(0.30f).setMinHandPresenceConfidence(0.34f).setMinTrackingConfidence(0.34f)
     .setRunningMode(RunningMode.LIVE_STREAM).setResultListener(this::onHandLandmarkResult)
     .setErrorListener { _ -> inferenceBusy = false }.build()
    handLandmarker = HandLandmarker.createFromOptions(this, opts)
    break
   } catch (_: Exception) {}
  }
 }
 private fun startCameraPipeline() {
  val future = ProcessCameraProvider.getInstance(this)
  future.addListener({ cameraProvider = future.get(); if (isRunning && !cameraBound) cameraProvider?.let { bindAnalysisUseCase(it) } }, ContextCompat.getMainExecutor(this))
 }
 private fun bindAnalysisUseCase(provider: ProcessCameraProvider) {
  val exec = cameraExecutor ?: return
  runCatching {
   provider.unbindAll()
   val resSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(ResolutionStrategy(Size(480, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
   val rot = getCurrentDisplayRotation(); cachedDisplayRotation = rot
   val analysis = ImageAnalysis.Builder()
    .setResolutionSelector(resSelector).setTargetRotation(rot)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
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
  val stridePixels = plane.rowStride / plane.pixelStride.coerceAtLeast(4)
  val buf = plane.buffer; buf.rewind()
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
  val targetFps = when { AirTouchTelemetry.handDetected -> 30; (now - lastHandDetectedTimeMs) < 12000L -> 20; else -> 15 }
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
     if (farSearchToggle) transformMatrix.postScale(1.38f, 1.38f)
    } else farSearchToggle = false
    transformMatrix.postTranslate(outW / 2f, outH / 2f)
    tCanvas?.drawColor(Color.BLACK); tCanvas?.drawBitmap(rawBmp, transformMatrix, paintFilter)
    val safeTs = maxOf(now, lastSubmittedTimestampMs + 1L); lastSubmittedTimestampMs = safeTs
    inferenceBusy = true; lastInferenceStartMs = now
    landmarker.detectAsync(BitmapImageBuilder(tBmp).build(), safeTs)
   } finally { if (rawBmp !== fallbackRawBitmap) rawBmp.recycle() }
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
   .setContentTitle("AirTouch AI v3.5 실행 중")
   .setContentText("포인터·클릭 개별 토글 · 손바닥 스크롤 · 12종 손가락 제스처")
   .setContentIntent(openPending).addAction(android.R.drawable.ic_menu_close_clear_cancel, "즉시 끄기", stopPending).setOngoing(true).build()
 }
}

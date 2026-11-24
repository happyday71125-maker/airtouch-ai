package com.prolauncher.airtouch

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.*

class AirTouchTileService : TileService() {
 override fun onStartListening() { super.onStartListening(); syncTileState(AirTouchTrackingService.isRunning) }
 override fun onClick() { super.onClick(); syncTileState(LauncherAirTouchBridge.toggle(this)) }
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
 private lateinit var recenterBtn: Button
 private lateinit var quickModePresetBtn: Button
 private lateinit var practiceBtn: Button
 private lateinit var speedSeek: SeekBar
 private lateinit var pinchSeek: SeekBar
 private lateinit var scrollSeek: SeekBar
 private lateinit var speedLabel: TextView
 private lateinit var pinchLabel: TextView
 private lateinit var scrollSensLabel: TextView
 private lateinit var mousePointerSwitch: Switch
 private lateinit var pinchClickSwitch: Switch
 private lateinit var thumbTriggerSwitch: Switch
 private lateinit var swipeSwitch: Switch
 private lateinit var invertSwitch: Switch
 private val poseUpdaters = mutableListOf<() -> Unit>()
 private var suppressSwitchCallbacks = false
 private var practiceTargetHitCount = 0
 private var lastObservedRunning: Boolean? = null
 private val uiHandler = Handler(Looper.getMainLooper())
 private val uiRefreshRunnable = object : Runnable {
  override fun run() {
   val runningNow = LauncherAirTouchBridge.isRunning()
   if (lastObservedRunning != runningNow) { lastObservedRunning = runningNow; refreshDashboardState() }
   else updateLiveTelemetryCard()
   uiHandler.postDelayed(this, 160L)
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); config = AirTouchConfig(this); setContentView(buildUi()) }
 override fun onResume() { super.onResume(); refreshDashboardState(); uiHandler.post(uiRefreshRunnable) }
 override fun onPause() { uiHandler.removeCallbacks(uiRefreshRunnable); super.onPause() }
 override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  refreshDashboardState()
  if (requestCode == 1002 && hasCameraPermission() && LauncherAirTouchBridge.isAccessibilityEnabled(this) && !LauncherAirTouchBridge.isRunning()) {
   LauncherAirTouchBridge.start(this); uiHandler.postDelayed({ refreshDashboardState() }, 150L)
  }
 }
 private fun buildUi(): ScrollView {
  val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D0F12")) }
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(40)) }
  val pInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
  @Suppress("DEPRECATION") val vCode = pInfo?.versionCode ?: 0
  root.addView(TextView(this).apply {
   text = "AIRTOUCH AI v3.5 (Build $vCode)"
   textSize = 18.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#00E676"))
  })
  root.addView(TextView(this).apply {
   text = "포인터·클릭 개별 토글(손가락 모션 전용 지원) + 원샷 클릭 + 손바닥 스크롤 + 12종 제스처"
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
  root.addView(TextView(this).apply {
   text = "[ v3.5 핵심 동작 안내 (포인터·클릭 토글 및 손가락 모션 전용 지원) ]\n" +
    "• \u261D\uFE0F 마우스 포인터 모드 (초록 링) : 토글 OFF 시 커서를 숨기고 손 이동 추적을 끕니다.\n" +
    "• \uD83D\uDC4C 마우스 클릭 기능 (노란 링) : 토글 OFF 시 엄지·검지가 닿아도 클릭되지 않아 오클릭이 0%입니다.\n" +
    "• \uD83D\uDD90\uFE0F 손가락 모션 전용 활용 : 포인터·클릭을 모두 끄면 손바닥 스크롤과 12종 손가락 제스처만 깔끔하게 작동합니다."
   textSize = 11.8f; setTextColor(Color.parseColor("#E0E0E0"))
   setBackgroundColor(Color.parseColor("#131A22")); setPadding(dp(12), dp(10), dp(12), dp(10))
   layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(10) }
  })
  root.addView(createBtn("[ 1. 접근성 서비스(터치·스크롤 권한) 켜기 ]") { LauncherAirTouchBridge.openAccessibilitySettings(this) })
  root.addView(createBtn("[ 2. 전면 카메라 권한 허용 ]") { requestCameraAndNotifPermissions(1001) })
  toggleBtn = createBtn("[ 3. AirTouch 제스처 추적 시작 ]", true) {
   if (!hasCameraPermission()) { requestCameraAndNotifPermissions(1002); return@createBtn }
   if (!LauncherAirTouchBridge.isAccessibilityEnabled(this)) {
    Toast.makeText(this, "접근성 설정에서 'AirTouch 제스처 컨트롤'을 켜주세요.", Toast.LENGTH_LONG).show()
    LauncherAirTouchBridge.openAccessibilitySettings(this); return@createBtn
   }
   LauncherAirTouchBridge.toggle(this); uiHandler.postDelayed({ refreshDashboardState() }, 140L)
  }
  root.addView(toggleBtn)
  recenterBtn = createBtn("[ 커서 위치 화면 정중앙으로 리셋 ]") { LauncherAirTouchBridge.recenter(this) }
  root.addView(recenterBtn)
  practiceBtn = createBtn("★ [정밀 클릭·스크롤 테스트 과녁] 전체클릭: 0회 (과녁명중 0) | 스크롤: 0회") {
   practiceTargetHitCount++; updatePracticeButtonText(); practiceBtn.setBackgroundColor(Color.parseColor("#1B5E20"))
  }
  root.addView(practiceBtn)
  root.addView(createHeader("INPUT MODE TOGGLES (마우스 포인터 · 클릭 · 스크롤 개별 ON/OFF)"))
  quickModePresetBtn = createBtn("") {
   val switchToMotionOnly = config.mousePointerEnabled || config.pinchClickEnabled
   config.mousePointerEnabled = !switchToMotionOnly
   config.pinchClickEnabled = !switchToMotionOnly
   if (switchToMotionOnly) LauncherAirTouchBridge.recenter(this)
   AirTouchAccessibilityService.instance?.refreshOverlayVisibility()
   syncControlsFromConfig()
   refreshDashboardState()
   val msg = if (switchToMotionOnly) "손가락 모션 전용 모드(포인터·클릭 OFF)로 전환되었습니다." else "에어마우스 포인터 및 클릭 모드가 켜졌습니다."
   Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
  }
  root.addView(quickModePresetBtn)
  mousePointerSwitch = Switch(this).apply {
   text = "\u261D\uFE0F 마우스 포인터 모드 사용 (화면 커서 표시 및 손 이동 추적)"
   setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6))
   setOnCheckedChangeListener { _, checked ->
    if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
    config.mousePointerEnabled = checked
    if (!checked) LauncherAirTouchBridge.recenter(this@MainActivity)
    AirTouchAccessibilityService.instance?.refreshOverlayVisibility()
    updateDependentControlStates()
    refreshDashboardState()
   }
  }
  root.addView(mousePointerSwitch)
  pinchClickSwitch = Switch(this).apply {
   text = "\uD83D\uDC4C 마우스 단일 클릭 기능 사용 (엄지·검지 맞닿음 및 방아쇠 클릭)"
   setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(4))
   setOnCheckedChangeListener { _, checked ->
    if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
    config.pinchClickEnabled = checked
    updateDependentControlStates()
    refreshDashboardState()
   }
  }
  root.addView(pinchClickSwitch)
  thumbTriggerSwitch = Switch(this).apply {
   text = "   └ \u261D\uFE0F+\uD83D\uDC4D 엄지 방아쇠 클릭 보조 사용 (검지 옆마디에 엄지 붙여 클릭)"
   setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, dp(6))
   setOnCheckedChangeListener { _, checked ->
    if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
    config.thumbTriggerClickEnabled = checked
   }
  }
  root.addView(thumbTriggerSwitch)
  swipeSwitch = Switch(this).apply {
   text = "\uD83D\uDD90\uFE0F 손바닥 스와이프 스크롤 기능 사용 (상·하·좌·우 쓸기)"
   setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(4))
   setOnCheckedChangeListener { _, checked ->
    if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
    config.swipeScrollEnabled = checked
    updateDependentControlStates()
    refreshDashboardState()
   }
  }
  root.addView(swipeSwitch)
  invertSwitch = Switch(this).apply {
   text = "   └ 손바닥 상하 스크롤 방향 반전 (기본 OFF: 손을 위로 쓸면 아래 내용 보기)"
   setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, dp(8))
   setOnCheckedChangeListener { _, checked ->
    if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
    config.invertPalmScroll = checked
   }
  }
  root.addView(invertSwitch)
  root.addView(createHeader("DISPLAY LAYOUT TOGGLES (오버레이 및 모니터 표시 설정)"))
  root.addView(Switch(this).apply {
   text = "우측 하단 21개 손가락 관절 미니 뷰 표시"; setTextColor(Color.WHITE); isChecked = config.showSkeletonMiniView; setPadding(0, dp(6), 0, dp(6))
   setOnCheckedChangeListener { _, checked -> config.showSkeletonMiniView = checked; AirTouchAccessibilityService.instance?.refreshOverlayVisibility() }
  })
  root.addView(Switch(this).apply {
   text = "상단 플로팅 현재 상태 배지 표시"; setTextColor(Color.WHITE); isChecked = config.showOverlayStatusPill; setPadding(0, dp(6), 0, dp(6))
   setOnCheckedChangeListener { _, checked -> config.showOverlayStatusPill = checked; AirTouchAccessibilityService.instance?.refreshOverlayVisibility() }
  })
  root.addView(Switch(this).apply {
   text = "앱 내 실시간 인식 상태 모니터 표시"; setTextColor(Color.WHITE); isChecked = config.showLiveMonitorCard; setPadding(0, dp(6), 0, dp(8))
   setOnCheckedChangeListener { _, checked ->
    config.showLiveMonitorCard = checked
    val vis = if (checked) View.VISIBLE else View.GONE
    monitorHeaderView.visibility = vis; liveTelemetryText.visibility = vis
   }
  })
  monitorHeaderView = createHeader("LIVE MONITOR (실시간 모드 · 클릭비율 · 방아쇠비율)")
  liveTelemetryText = TextView(this).apply {
   textSize = 12.2f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#FFD740"))
   setBackgroundColor(Color.parseColor("#141920")); setPadding(dp(12), dp(12), dp(12), dp(12))
  }
  val initVis = if (config.showLiveMonitorCard) View.VISIBLE else View.GONE
  monitorHeaderView.visibility = initVis; liveTelemetryText.visibility = initVis
  root.addView(monitorHeaderView); root.addView(liveTelemetryText)
  root.addView(createHeader("CORE SENSITIVITY (포인터 · 단일 클릭 · 스크롤 감도)"))
  speedLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12.5f }; root.addView(speedLabel)
  speedSeek = SeekBar(this).apply {
   max = 260
   setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
     val v = 0.6f + (p / 100f); if (fromUser) config.cursorSpeed = v
     speedLabel.text = "1) \u261D\uFE0F 포인터 이동 속도: ${String.format("%.2f", v)}x"
    }
    override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
   })
  }
  root.addView(speedSeek)
  pinchLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12.5f; setPadding(0, dp(10), 0, 0) }; root.addView(pinchLabel)
  pinchSeek = SeekBar(this).apply {
   max = 34
   setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
     val v = 0.24f + (p / 100f); if (fromUser) config.pinchRatioThreshold = v
     pinchLabel.text = "2) \uD83D\uDC4C 단일 클릭 민감도: ${String.format("%.2f", v)} (기본 0.42 · 높일수록 살짝만 맞대도 클릭)"
    }
    override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
   })
  }
  root.addView(pinchSeek)
  scrollSensLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12.5f; setPadding(0, dp(10), 0, 0) }; root.addView(scrollSensLabel)
  scrollSeek = SeekBar(this).apply {
   max = 105
   setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
     val v = 0.045f + (p / 1000f); if (fromUser) config.palmSwipeThreshold = v
     scrollSensLabel.text = "3) \uD83D\uDD90\uFE0F 손바닥 스크롤 요구 폭: ${String.format("%.3f", v)} (낮출수록 가볍게 쓸어도 스크롤)"
    }
    override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
   })
  }
  root.addView(scrollSeek)
  root.addView(createHeader("12 HAND GESTURE POSES (손을 멈추고 0.6초 유지 시 실행 · 터치하여 변경)"))
  poseUpdaters.clear()
  for (pose in HandPose.values()) {
   val rowBtn = createBtn("") {}
   val updater: () -> Unit = { rowBtn.text = "${pose.labelKo}  ->  [${config.getActionForPose(pose).titleKo}]\n  • ${pose.howToKo}" }
   poseUpdaters.add(updater); updater()
   rowBtn.setOnClickListener {
    val actions = AirTouchAction.values()
    AlertDialog.Builder(this).setTitle("${pose.labelKo} 실행 동작 선택")
     .setItems(actions.map { it.titleKo }.toTypedArray()) { _, w -> config.setActionForPose(pose, actions[w]); updater() }.show()
   }
   root.addView(rowBtn)
  }
  root.addView(createBtn("[ 모든 모드·감도 및 12종 제스처 기본값으로 초기화 ]") {
   config.resetDefaults()
   AirTouchAccessibilityService.instance?.refreshOverlayVisibility()
   syncControlsFromConfig()
   refreshDashboardState()
   Toast.makeText(this, "기본 설정으로 초기화되었습니다.", Toast.LENGTH_SHORT).show()
  })
  syncControlsFromConfig()
  scroll.addView(root)
  return scroll
 }
 private fun updateDependentControlStates() {
  val ptrOn = config.mousePointerEnabled
  val clkOn = config.pinchClickEnabled
  val scrOn = config.swipeScrollEnabled
  val anyMouseActive = ptrOn || clkOn
  quickModePresetBtn.text = if (anyMouseActive) {
   "[ \uD83D\uDD90\uFE0F 손가락 모션 전용으로 한번에 전환 (포인터·클릭 일괄 OFF) ]"
  } else {
   "[ \u261D\uFE0F 에어마우스 모드 다시 켜기 (포인터·클릭 일괄 ON) ]"
  }
  quickModePresetBtn.setBackgroundColor(Color.parseColor(if (anyMouseActive) "#263238" else "#00695C"))
  recenterBtn.isEnabled = ptrOn; recenterBtn.alpha = if (ptrOn) 1.0f else 0.45f
  speedSeek.isEnabled = ptrOn; speedLabel.alpha = if (ptrOn) 1.0f else 0.45f
  pinchSeek.isEnabled = clkOn; pinchLabel.alpha = if (clkOn) 1.0f else 0.45f
  thumbTriggerSwitch.isEnabled = clkOn; thumbTriggerSwitch.alpha = if (clkOn) 1.0f else 0.45f
  scrollSeek.isEnabled = scrOn; scrollSensLabel.alpha = if (scrOn) 1.0f else 0.45f
  invertSwitch.isEnabled = scrOn; invertSwitch.alpha = if (scrOn) 1.0f else 0.45f
 }
 private fun syncControlsFromConfig() {
  suppressSwitchCallbacks = true
  try {
   mousePointerSwitch.isChecked = config.mousePointerEnabled
   pinchClickSwitch.isChecked = config.pinchClickEnabled
   thumbTriggerSwitch.isChecked = config.thumbTriggerClickEnabled
   swipeSwitch.isChecked = config.swipeScrollEnabled
   invertSwitch.isChecked = config.invertPalmScroll
  } finally {
   suppressSwitchCallbacks = false
  }
  speedSeek.progress = ((config.cursorSpeed - 0.6f) * 100f).roundToInt().coerceIn(0, 260)
  speedLabel.text = "1) \u261D\uFE0F 포인터 이동 속도: ${String.format("%.2f", config.cursorSpeed)}x"
  pinchSeek.progress = ((config.pinchRatioThreshold - 0.24f) * 100f).roundToInt().coerceIn(0, 34)
  pinchLabel.text = "2) \uD83D\uDC4C 단일 클릭 민감도: ${String.format("%.2f", config.pinchRatioThreshold)} (기본 0.42 · 높일수록 살짝만 맞대도 클릭)"
  scrollSeek.progress = ((config.palmSwipeThreshold - 0.045f) * 1000f).roundToInt().coerceIn(0, 105)
  scrollSensLabel.text = "3) \uD83D\uDD90\uFE0F 손바닥 스크롤 요구 폭: ${String.format("%.3f", config.palmSwipeThreshold)} (낮출수록 가볍게 쓸어도 스크롤)"
  poseUpdaters.forEach { it() }
  updateDependentControlStates()
 }
 private fun updatePracticeButtonText() {
  practiceBtn.text = "★ [정밀 클릭·스크롤 테스트 과녁] 전체클릭: ${AirTouchTelemetry.clickCount}회 (과녁명중 ${practiceTargetHitCount}) | 스크롤: ${AirTouchTelemetry.scrollCount}회"
 }
 private fun updateLiveTelemetryCard() {
  updatePracticeButtonText()
  if (!config.showLiveMonitorCard) return
  if (!LauncherAirTouchBridge.isRunning()) { liveTelemetryText.text = "• 대기 중 ('3. AirTouch 제스처 추적 시작'을 눌러주세요)"; return }
  val clickInfo = if (config.pinchClickEnabled) {
   "${String.format("%.2f", AirTouchTelemetry.pinchRatio)}(임계${String.format("%.2f", config.pinchRatioThreshold)}) · 방아쇠:${String.format("%.2f", AirTouchTelemetry.thumbSideRatio)}"
  } else "클릭 OFF (오클릭 차단됨)"
  liveTelemetryText.text =
   "• 손 인식 : ${if (AirTouchTelemetry.handDetected) "● 정상 (${AirTouchTelemetry.cameraResolutionText} · 지연 ${AirTouchTelemetry.inferenceLatencyMs}ms)" else "○ 원거리 부스트 탐색 중 (1.38x 교차 스캔)"}\n" +
   "• 거리/방향: 약 ${AirTouchTelemetry.estimatedDistanceCm}cm · ${AirTouchTelemetry.orientationText}\n" +
   "• 손가락  : ${AirTouchTelemetry.fingerStatesText}\n" +
   "• 현재모드: ${AirTouchTelemetry.currentModeText}\n" +
   "• 클릭상태: $clickInfo · 최근:${AirTouchTelemetry.lastTriggeredActionText}"
 }
 private fun refreshDashboardState() {
  val running = LauncherAirTouchBridge.isRunning(); lastObservedRunning = running
  val pssMb = runCatching { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss / 1024 }.getOrDefault(0)
  val inputSummary = when {
   config.mousePointerEnabled && config.pinchClickEnabled -> "풀 에어마우스 (포인터 ON · 클릭 ON)"
   config.mousePointerEnabled && !config.pinchClickEnabled -> "포인터+모션 (포인터 ON · 클릭 OFF)"
   !config.mousePointerEnabled && config.pinchClickEnabled -> "커서 숨김 클릭+모션 (포인터 OFF · 클릭 ON)"
   else -> "손가락 모션 전용 (포인터 OFF · 클릭 OFF)"
  }
  statusText.text =
   "• 카메라 권한 : ${if (hasCameraPermission()) "허용됨 (OK)" else "미허용 (터치하여 허용)"}\n" +
   "• 접근성 권한 : ${if (LauncherAirTouchBridge.isAccessibilityEnabled(this)) "활성화됨 (OK)" else "비활성 (설정 필요)"}\n" +
   "• 입력 구성   : $inputSummary\n" +
   "• 엔진 & RAM  : ${if (running) "실행 중 (PSS 약 ${pssMb}MB)" else "중지됨 (카메라·모델 0MB 유휴 상태)"}"
  toggleBtn.text = if (running) "[ AirTouch 제스처 추적 중지 (RAM 즉시 반환) ]" else "[ 3. AirTouch 제스처 추적 시작 ]"
  updateLiveTelemetryCard()
 }
 private fun requestCameraAndNotifPermissions(reqCode: Int) {
  val perms = mutableListOf(Manifest.permission.CAMERA)
  if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
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

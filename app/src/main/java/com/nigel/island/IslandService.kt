package com.nigel.island

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.Interpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Gentle iOS-style spring: small overshoot, then settles. */
class SpringInterp : Interpolator {
    override fun getInterpolation(input: Float): Float {
        if (input >= 1f) return 1f
        val t = input.toDouble()
        return (1.0 - exp(-8.0 * t) * cos(9.0 * t)).toFloat()
    }
}

/** The little music "equaliser" bars. */
class WaveView(ctx: Context) : View(ctx) {
    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var active: Boolean = true
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ph = floatArrayOf(0f, 1.7f, 3.1f, 4.6f)

    override fun onDraw(c: Canvas) {
        p.color = color
        val n = ph.size
        val bw = width / (n * 2f - 1f)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val f = if (active) 0.3f + 0.7f * (0.5f + 0.5f * sin(t * (4.5f + i * 1.3f) + ph[i])) else 0.2f
            val h = height * f
            val x = i * 2 * bw
            c.drawRoundRect(x, (height - h) / 2f, x + bw, (height + h) / 2f, bw / 2f, bw / 2f, p)
        }
        if (active) postInvalidateOnAnimation()
    }
}

/** The island body: glossy 3D black glass with a flowing colour rim. */
class PillView(ctx: Context) : FrameLayout(ctx) {
    var radius = 0f
    var rimSpan = 1000f
    var rimColors: IntArray = intArrayOf(0xFF00E5FF.toInt(), 0xFFB14DFF.toInt(), 0xFFFF4D8D.toInt(), 0xFF00E5FF.toInt())
        set(v) { field = v; rimShader = null; invalidate() }
    var phase = 0f
        set(v) { field = v; invalidate() }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF }
    private var rimShader: LinearGradient? = null
    private var bodyShader: LinearGradient? = null
    private var bodyH = -1f
    private val m = Matrix()
    private val r = RectF()

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density

        // body: lighter top fading to deep black = a little depth
        val ge = min(h, 60f * d)
        if (bodyShader == null || bodyH != ge) {
            bodyShader = LinearGradient(0f, 0f, 0f, ge, 0xFF30303A.toInt(), Color.BLACK, Shader.TileMode.CLAMP)
            bodyH = ge
        }
        body.shader = bodyShader
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, radius, radius, body)

        // flowing colour rim
        val sw = 1.6f * d
        val rs = rimShader ?: LinearGradient(0f, 0f, rimSpan, 0f, rimColors, null, Shader.TileMode.REPEAT).also { rimShader = it }
        m.setTranslate(phase * rimSpan, 0f)
        rs.setLocalMatrix(m)
        rim.shader = rs
        rim.strokeWidth = sw
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        c.drawRoundRect(r, radius - sw / 2, radius - sw / 2, rim)

        // glossy top edge
        c.save()
        c.clipRect(0f, 0f, w, h * 0.45f)
        gloss.strokeWidth = d
        r.set(sw + d * 0.5f, sw + d * 0.5f, w - sw - d * 0.5f, h - sw - d * 0.5f)
        c.drawRoundRect(r, radius - sw, radius - sw, gloss)
        c.restore()

        // soft specular streak (only on the small pill)
        if (h < 80f * d) {
            val sh = 2f * d
            r.set(h * 0.5f, sw + 2f * d, w - h * 0.5f, sw + 2f * d + sh)
            c.drawRoundRect(r, sh / 2, sh / 2, streak)
        }
    }
}

class IslandService : Service() {

    companion object {
        var instance: IslandService? = null
        var callerName: String? = null

        // ---- EASY TWEAKS ----
        const val SIDE_MM = 10f         // millimetres the pill extends each side of the camera (10 = 1 cm)
        const val GROW = 26             // dp: extra width when something is showing
        const val PAD = 1               // dp: pill is this much bigger than the camera hole
        const val NOTIF_MS = 4500L
        const val CHARGE_MS = 3500L
        const val EXPAND_TIMEOUT = 12000L

        const val GREEN = 0xFF34C759.toInt()
        const val RED = 0xFFFF3B30.toInt()
        const val GREY = 0xFF9A9A9E.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private enum class Kind { NONE, MEDIA, CALL_RING, CALL_ACTIVE, CHARGE, NOTIF }

    private class NotifData(val id: Int, val pkg: String, val label: String, val title: String, val text: String, val pi: PendingIntent?)

    private lateinit var wm: WindowManager
    private lateinit var pill: PillView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var gestures: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private var vib: Vibrator? = null

    private var cutW = 0
    private var cutH = 0
    private var cutTop = 0
    private var curRadius = 0f
    private var anim: ValueAnimator? = null
    private var content: View? = null
    private var contentKey = ""
    private var visibleOk = true
    private var screenOn = true
    private var rimPhase = 0f
    private var rimKey = ""

    private var kind = Kind.NONE
    private var expanded = false
    private var userExpanded = false

    private var notif: NotifData? = null
    private var notifCounter = 0
    private var unread: List<String> = emptyList()
    private var trayHidden = false
    private var charging = false
    private var callState = 0
    private var callStart = 0L
    private var demoCall = false
    private var telCb: TelephonyCallback? = null

    private var controller: MediaController? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var mediaTitle: String? = null
    private var mediaArtist: String? = null
    private var mediaArt: Bitmap? = null
    private var mediaDuration = 0L
    private var accent = Color.WHITE
    private var mediaDismissed = false
    private var lastPlaying = false
    private var seeking = false

    private var waveRef: WaveView? = null
    private var playRef: ImageView? = null
    private var progressRef: SeekBar? = null
    private var elapsedRef: TextView? = null
    private var totalRef: TextView? = null
    private var artRef: ImageView? = null
    private var artAnim: ValueAnimator? = null

    private var bubble: ImageView? = null
    private var blp: WindowManager.LayoutParams? = null
    private var bubbleOn = false

    private val notifOff = Runnable { notif = null; render() }
    private val chargeOff = Runnable { charging = false; render() }
    private val autoCollapse = Runnable { userExpanded = false; render() }
    private val tick = object : Runnable {
        override fun run() {
            if (kind == Kind.MEDIA && expanded) {
                updateProgress()
                handler.postDelayed(this, 500)
            }
        }
    }
    private val rimTick = object : Runnable {
        override fun run() {
            rimPhase = (rimPhase + 0.004f) % 1f
            pill.phase = rimPhase
            if (screenOn && visibleOk) handler.postDelayed(this, 33)
        }
    }

    private val chargeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { onPowerConnected() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action == Intent.ACTION_SCREEN_ON
            startRim()
        }
    }

    private val mediaCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val p = isPlaying()
            if (p && !lastPlaying) mediaDismissed = false
            lastPlaying = p
            render()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) { loadMeta(); render() }
        override fun onSessionDestroyed() { controller = null; render() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun mm(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics).toInt()
    private fun idleW() = cutW + mm(SIDE_MM) * 2

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Island", NotificationManager.IMPORTANCE_MIN))
        startForeground(
            1,
            Notification.Builder(this, "island")
                .setContentTitle("Atoll is running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        )
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        vib = getSystemService(Vibrator::class.java)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (!expanded && (kind == Kind.MEDIA || kind == Kind.CALL_ACTIVE)) {
                    haptic(); userExpanded = true; render()
                }
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val horiz = abs(vx) > abs(vy)
                if (!horiz && vy < -dp(300)) { swipeUp(); return true }
                if (horiz && abs(vx) > dp(400)) { swipeSide(vx); return true }
                return false
            }
        })
        readCutout()
        buildPill()
        registerReceiver(chargeReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        refreshMedia()
        registerCall()
        render(false)
        startRim()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshMedia()
        registerCall()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val portrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        visibleOk = portrait
        pill.visibility = if (portrait) View.VISIBLE else View.GONE
        if (portrait) {
            readCutout()
            lp.y = cutTop
            contentKey = ""
            render(false)
        }
        startRim()
        updateBubble()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        artAnim?.cancel()
        try { unregisterReceiver(chargeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { controller?.unregisterCallback(mediaCb) } catch (_: Exception) {}
        try {
            sessionsListener?.let { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(it) }
        } catch (_: Exception) {}
        try { telCb?.let { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) } } catch (_: Exception) {}
        try { wm.removeView(pill) } catch (_: Exception) {}
        removeBubble()
        super.onDestroy()
    }

    private fun startRim() {
        handler.removeCallbacks(rimTick)
        if (screenOn && visibleOk) handler.post(rimTick)
    }

    // ---------------------------------------------------------------- window

    private fun readCutout() {
        var r: Rect? = null
        try {
            val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            r = d?.cutout?.boundingRects?.firstOrNull()
        } catch (_: Exception) {}
        if (r != null && r.width() > 0) {
            cutW = r.width() + dp(PAD * 2)
            cutH = r.height() + dp(PAD * 2)
            cutTop = (r.top - dp(PAD)).coerceAtLeast(0)
        } else {
            cutW = dp(30); cutH = dp(30); cutTop = dp(8)
        }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = if (touchable) flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = cutTop
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun buildPill() {
        lp = overlayParams(idleW(), cutH, true)
        curRadius = radiusFor(cutH)
        pill = PillView(this).apply {
            radius = curRadius
            rimSpan = resources.displayMetrics.widthPixels.toFloat()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, lp.width, lp.height, curRadius)
                }
            }
            setOnTouchListener { _, e -> onPillTouch(e) }
        }
        wm.addView(pill, lp)
    }

    private fun radiusFor(h: Int) = min(h / 2f, dp(44).toFloat())

    private fun setSize(w: Int, h: Int) {
        lp.width = w.coerceAtLeast(idleW())
        lp.height = h.coerceAtLeast(cutH)
        curRadius = radiusFor(lp.height)
        pill.radius = curRadius
        try { wm.updateViewLayout(pill, lp) } catch (_: Exception) {}
        pill.invalidateOutline()
        positionBubble()
    }

    private fun animateTo(w: Int, h: Int, animate: Boolean) {
        anim?.cancel()
        val sw = lp.width
        val sh = lp.height
        if (!animate || (sw == w && sh == h)) { setSize(w, h); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = SpringInterp()
            addUpdateListener {
                val f = it.animatedValue as Float
                setSize((sw + (w - sw) * f).toInt(), (sh + (h - sh) * f).toInt())
            }
            start()
        }
    }

    // ---------------------------------------------------------------- touch

    private fun showTray() = unread.isNotEmpty() && !trayHidden

    private fun onPillTouch(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_OUTSIDE) {
            if (expanded && (kind == Kind.MEDIA || kind == Kind.CALL_ACTIVE)) { userExpanded = false; render() }
            return false
        }
        if (kind != Kind.NONE || showTray()) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pill.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start()
                    if (userExpanded) {
                        handler.removeCallbacks(autoCollapse)
                        handler.postDelayed(autoCollapse, EXPAND_TIMEOUT)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(SpringInterp()).start()
            }
        }
        return gestures.onTouchEvent(e)
    }

    private fun haptic() {
        try { vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) } catch (_: Exception) {}
    }

    private fun pop(v: View) {
        haptic()
        v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(SpringInterp()).start()
        }.start()
    }

    private fun onTap() {
        when {
            kind == Kind.MEDIA && !expanded -> { pop(pill); togglePlay() }
            kind == Kind.NOTIF -> openApp()
            kind == Kind.NONE && showTray() -> { haptic(); openShade() }
        }
    }

    private fun swipeUp() {
        when {
            expanded && (kind == Kind.MEDIA || kind == Kind.CALL_ACTIVE) -> { userExpanded = false; render() }
            kind == Kind.NOTIF || kind == Kind.CHARGE || kind == Kind.MEDIA -> dismissCurrent()
        }
    }

    private fun swipeSide(vx: Float) {
        when {
            kind == Kind.MEDIA && !expanded -> {
                pop(pill)
                if (vx < 0) controller?.transportControls?.skipToNext()
                else controller?.transportControls?.skipToPrevious()
            }
            kind == Kind.NOTIF || kind == Kind.CHARGE -> dismissCurrent()
            kind == Kind.NONE && showTray() -> { trayHidden = true; render() }
        }
    }

    private fun dismissCurrent() {
        when (kind) {
            Kind.NOTIF -> { handler.removeCallbacks(notifOff); notif = null }
            Kind.CHARGE -> { handler.removeCallbacks(chargeOff); charging = false }
            Kind.MEDIA -> { mediaDismissed = true; userExpanded = false }
            Kind.CALL_ACTIVE -> userExpanded = false
            else -> return
        }
        render()
    }

    private fun openShade() {
        try {
            val sb = getSystemService("statusbar")
            sb?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sb)
        } catch (_: Exception) {}
    }

    private fun openApp() {
        try {
            when (kind) {
                Kind.NOTIF -> notif?.pi?.send()
                Kind.MEDIA -> {
                    val c = controller
                    val si = c?.sessionActivity
                    if (si != null) {
                        si.send()
                    } else if (c != null) {
                        packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(it)
                        }
                    }
                }
                else -> {}
            }
        } catch (_: Exception) {}
        if (kind == Kind.NOTIF) { handler.removeCallbacks(notifOff); notif = null; render() }
    }

    // ---------------------------------------------------------------- state -> screen

    private fun mediaVisible() =
        controller != null && !mediaTitle.isNullOrEmpty() && !mediaDismissed && (isPlaying() || userExpanded)

    private fun isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun render(animate: Boolean = true) {
        val k = when {
            callState == 1 -> Kind.CALL_RING
            notif != null -> Kind.NOTIF
            charging -> Kind.CHARGE
            callState == 2 -> Kind.CALL_ACTIVE
            mediaVisible() -> Kind.MEDIA
            else -> Kind.NONE
        }
        if (k != kind) userExpanded = false
        val exp = when (k) {
            Kind.CALL_RING, Kind.NOTIF -> true
            Kind.MEDIA, Kind.CALL_ACTIVE -> userExpanded
            else -> false
        }
        kind = k
        expanded = exp
        val (w, h) = sizeFor(k, exp)
        val trayKey = if (k == Kind.NONE && showTray()) unread.joinToString(",") else ""
        val key = "$k|$exp|${notif?.id}|$mediaTitle|$mediaArtist|${System.identityHashCode(mediaArt)}|$callerName|$demoCall|$trayKey"
        if (key != contentKey) {
            contentKey = key
            val v: View? = if (k == Kind.NONE && !showTray()) null else buildContent(k, exp)
            swap(v, w, h, animate)
        }
        applyRim(k)
        animateTo(w, h, animate)
        updateMediaDynamic()
        updateBubble()

        handler.removeCallbacks(tick)
        if (k == Kind.MEDIA && exp) handler.post(tick)
        handler.removeCallbacks(autoCollapse)
        if (userExpanded) handler.postDelayed(autoCollapse, EXPAND_TIMEOUT)
    }

    private fun applyRim(k: Kind) {
        val (key, colors) = when {
            k == Kind.MEDIA && mediaArt != null -> "m$accent" to palette(accent)
            k == Kind.CHARGE || k == Kind.CALL_RING || k == Kind.CALL_ACTIVE ->
                "g" to intArrayOf(GREEN, 0xFF30D5C8.toInt(), 0xFFB6FF3B.toInt(), GREEN)
            else -> "d" to intArrayOf(0xFF00E5FF.toInt(), 0xFFB14DFF.toInt(), 0xFFFF4D8D.toInt(), 0xFF00E5FF.toInt())
        }
        if (key != rimKey) {
            rimKey = key
            pill.rimColors = colors
        }
    }

    private fun palette(c: Int): IntArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        fun shift(deg: Float): Int = Color.HSVToColor(floatArrayOf((hsv[0] + deg + 360f) % 360f, hsv[1], hsv[2]))
        return intArrayOf(c, shift(45f), shift(-45f), c)
    }

    private fun sizeFor(k: Kind, exp: Boolean): Pair<Int, Int> {
        val bigW = min(resources.displayMetrics.widthPixels - dp(24), dp(380))
        return when {
            k == Kind.NONE -> Pair(idleW(), cutH)
            !exp -> Pair(idleW() + dp(GROW), cutH)
            k == Kind.MEDIA -> Pair(bigW, dp(212))
            k == Kind.NOTIF -> Pair(bigW, dp(112))
            k == Kind.CALL_RING -> Pair(bigW, dp(144))
            else -> Pair(bigW, dp(124))
        }
    }

    private fun swap(v: View?, w: Int, h: Int, animate: Boolean) {
        val old = content
        content = v
        if (old != null) {
            if (animate) old.animate().alpha(0f).setDuration(110).withEndAction { pill.removeView(old) }.start()
            else pill.removeView(old)
        }
        if (v != null) {
            pill.addView(v, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            if (animate) {
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(130).setDuration(200).start()
            }
        }
    }

    private fun buildContent(k: Kind, exp: Boolean): View {
        waveRef = null; playRef = null; progressRef = null; elapsedRef = null; totalRef = null; artRef = null
        return when (k) {
            Kind.MEDIA -> if (exp) mediaExpanded() else mediaCompact()
            Kind.CALL_RING -> callRing()
            Kind.CALL_ACTIVE -> if (exp) callActiveExpanded() else callActiveCompact()
            Kind.CHARGE -> chargeCompact()
            Kind.NOTIF -> notifExpanded()
            Kind.NONE -> trayView()
        }
    }

    // ---------------------------------------------------------------- view helpers

    private fun lpf(w: Int, h: Int, g: Int, ms: Int = 0, me: Int = 0) =
        FrameLayout.LayoutParams(w, h, g).apply { marginStart = ms; marginEnd = me }

    private fun llp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top }

    private fun tv(t: String?, sp: Float, c: Int, bold: Boolean = false, lines: Int = 1) = TextView(this).apply {
        text = t ?: ""
        textSize = sp
        setTextColor(c)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun roundedImage(radius: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius.toFloat()) }
        }
    }

    private fun appIcon(pkg: String?, radius: Int): ImageView {
        val iv = roundedImage(radius)
        try { if (pkg != null) iv.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) {}
        return iv
    }

    private fun setArt(iv: ImageView) {
        val a = mediaArt
        if (a != null) iv.setImageBitmap(a) else iv.setBackgroundColor(0xFF444444.toInt())
    }

    private fun pillBtn(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(40).toFloat() }
        setOnClickListener { pop(it); onClick() }
    }

    private fun iconBtn(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { pop(it); onClick() }
    }

    private fun chrono(sp: Float) = Chronometer(this).apply {
        base = callStart
        setTextColor(GREEN)
        textSize = sp
        start()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(10))
    }

    // ---------------------------------------------------------------- idle tray (app icons of unread notifications)

    private fun trayView(): View {
        val f = FrameLayout(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (p in unread.take(2)) {
            row.addView(appIcon(p, dp(5)), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
        }
        f.addView(row, lpf(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL, ms = dp(14)))
        if (unread.size > 2) {
            f.addView(tv("+${unread.size - 2}", 11f, Color.WHITE, true),
                lpf(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL, me = dp(18)))
        }
        return f
    }

    fun setUnread(pkgs: List<String>) {
        handler.post {
            if (pkgs.size > unread.size) trayHidden = false
            unread = pkgs
            if (pkgs.isEmpty()) trayHidden = false
            render()
        }
    }

    // ---------------------------------------------------------------- media

    private fun mediaCompact(): View {
        val f = FrameLayout(this)
        val art = roundedImage(dp(6)); setArt(art)
        artRef = art
        f.addView(art, lpf(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL, ms = dp(14)))
        val wave = WaveView(this).also { it.color = accent; it.active = isPlaying() }
        f.addView(wave, lpf(dp(20), dp(16), Gravity.END or Gravity.CENTER_VERTICAL, me = dp(16)))
        waveRef = wave
        return f
    }

    private fun mediaExpanded(): View {
        val col = column()
        val top = FrameLayout(this)
        val art = roundedImage(dp(10)); setArt(art)
        art.setOnClickListener { pop(it); openApp() }
        artRef = art
        top.addView(art, lpf(dp(44), dp(44), Gravity.START))
        top.addView(appIcon(controller?.packageName, dp(5)),
            lpf(dp(20), dp(20), Gravity.END or Gravity.CENTER_VERTICAL, me = dp(2)))
        val wave = WaveView(this).also { it.color = accent; it.active = isPlaying() }
        top.addView(wave, lpf(dp(26), dp(20), Gravity.END or Gravity.CENTER_VERTICAL, me = dp(32)))
        waveRef = wave
        col.addView(top, llp(MATCH, dp(44)))
        col.addView(tv(mediaTitle, 16f, Color.WHITE, true), llp(MATCH, WRAP, dp(8)))
        col.addView(tv(mediaArtist, 14f, GREY), llp(MATCH, WRAP))

        val bar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setSize(dp(10), dp(10))
            }
            thumbOffset = 0
            setPadding(dp(5), 0, dp(5), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) elapsedRef?.text = fmt(mediaDuration * p / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    seeking = true
                    handler.removeCallbacks(autoCollapse)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    controller?.transportControls?.seekTo(mediaDuration * sb.progress / 1000)
                    if (userExpanded) handler.postDelayed(autoCollapse, EXPAND_TIMEOUT)
                }
            })
        }
        progressRef = bar
        col.addView(bar, llp(MATCH, dp(24), dp(6)))
        val times = FrameLayout(this)
        val e = tv("0:00", 11f, GREY)
        val t = tv("", 11f, GREY)
        elapsedRef = e; totalRef = t
        times.addView(e, lpf(WRAP, WRAP, Gravity.START, ms = dp(5)))
        times.addView(t, lpf(WRAP, WRAP, Gravity.END, me = dp(5)))
        col.addView(times, llp(MATCH, WRAP))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val prev = iconBtn(android.R.drawable.ic_media_previous) { controller?.transportControls?.skipToPrevious() }
        val play = ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setImageResource(if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.BLACK)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { pop(it); togglePlay() }
        }
        val next = iconBtn(android.R.drawable.ic_media_next) { controller?.transportControls?.skipToNext() }
        playRef = play
        row.addView(prev, LinearLayout.LayoutParams(dp(52), dp(44)))
        row.addView(play, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(14); marginEnd = dp(14) })
        row.addView(next, LinearLayout.LayoutParams(dp(52), dp(44)))
        col.addView(row, llp(MATCH, dp(44), dp(4)))
        return col
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (isPlaying()) c.transportControls.pause() else c.transportControls.play()
    }

    private fun updateMediaDynamic() {
        val playing = isPlaying()
        waveRef?.let { it.color = accent; if (it.active != playing) it.active = playing }
        playRef?.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        updateProgress()

        // album art "breathes" while music plays
        val a = artRef
        if (a != null && playing) {
            if (artAnim == null) {
                artAnim = ValueAnimator.ofFloat(1f, 1.07f).apply {
                    duration = 1100
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener {
                        val s = it.animatedValue as Float
                        artRef?.scaleX = s
                        artRef?.scaleY = s
                    }
                    start()
                }
            }
        } else {
            artAnim?.cancel()
            artAnim = null
            a?.scaleX = 1f
            a?.scaleY = 1f
        }
    }

    private fun updateProgress() {
        val bar = progressRef ?: return
        if (seeking) return
        val st = controller?.playbackState ?: return
        val pos = if (st.state == PlaybackState.STATE_PLAYING)
            st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        else st.position
        val dur = mediaDuration
        bar.progress = if (dur > 0) ((pos * 1000) / dur).toInt().coerceIn(0, 1000) else 0
        elapsedRef?.text = fmt(pos)
        totalRef?.text = if (dur > 0) fmt(dur) else ""
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun loadMeta() {
        val md = controller?.metadata
        mediaTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        mediaArtist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        mediaArt = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        mediaDuration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        accent = accentOf(mediaArt)
    }

    private fun accentOf(b: Bitmap?): Int {
        if (b == null) return Color.WHITE
        return try {
            val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = min(1f, hsv[1] + 0.15f)
            hsv[2] = 0.95f
            Color.HSVToColor(hsv)
        } catch (_: Exception) { Color.WHITE }
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            try { controller?.unregisterCallback(mediaCb) } catch (_: Exception) {}
            controller = best
            best?.registerCallback(mediaCb, handler)
        }
        loadMeta()
        lastPlaying = isPlaying()
        render()
    }

    fun refreshMedia() {
        handler.post {
            try {
                val msm = getSystemService(MediaSessionManager::class.java)
                val cn = ComponentName(this, NotifListener::class.java)
                if (sessionsListener == null) {
                    val l = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }
                    msm.addOnActiveSessionsChangedListener(l, cn)
                    sessionsListener = l
                }
                pick(msm.getActiveSessions(cn))
            } catch (_: SecurityException) {}
        }
    }

    // ---------------------------------------------------------------- calls

    fun registerCall() {
        handler.post {
            try {
                val tm = getSystemService(TelephonyManager::class.java)
                telCb?.let { tm.unregisterTelephonyCallback(it) }
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { handler.post { onCallState(state) } }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                telCb = cb
            } catch (_: Exception) {}
        }
    }

    private fun onCallState(state: Int) {
        if (demoCall) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> callState = 1
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (callState != 2) callStart = SystemClock.elapsedRealtime()
                callState = 2
            }
            else -> { callState = 0; callerName = null }
        }
        render()
    }

    private fun name() = if (demoCall) "Test Caller" else (callerName ?: "Unknown caller")

    @Suppress("DEPRECATION", "MissingPermission")
    private fun acceptCall() {
        if (demoCall) { endDemo(); return }
        try { getSystemService(TelecomManager::class.java).acceptRingingCall() } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun endCall() {
        if (demoCall) { endDemo(); return }
        try { getSystemService(TelecomManager::class.java).endCall() } catch (_: Exception) {}
    }

    private fun endDemo() { demoCall = false; callState = 0; render() }

    fun demoIncomingCall() {
        handler.post {
            demoCall = true
            callState = 1
            render()
            handler.postDelayed({ if (demoCall) endDemo() }, 8000)
        }
    }

    private fun callRing(): View {
        val col = column()
        val top = FrameLayout(this)
        top.addView(tv("Incoming call", 12f, GREY), lpf(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        col.addView(top, llp(MATCH, cutH))
        col.addView(tv(name(), 20f, Color.WHITE, true), llp(MATCH, WRAP, dp(4)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pillBtn("Decline", RED) { endCall() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        row.addView(pillBtn("Accept", GREEN) { acceptCall() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        col.addView(row, llp(MATCH, dp(44), dp(12)))
        return col
    }

    private fun callActiveCompact(): View {
        val f = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.sym_action_call)
            setColorFilter(GREEN)
        }
        f.addView(icon, lpf(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL, ms = dp(14)))
        f.addView(chrono(12f), lpf(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL, me = dp(14)))
        return f
    }

    private fun callActiveExpanded(): View {
        val col = column()
        val top = FrameLayout(this)
        top.addView(tv("On call", 12f, GREY), lpf(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(chrono(13f), lpf(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, llp(MATCH, cutH))
        col.addView(tv(name(), 20f, Color.WHITE, true), llp(MATCH, WRAP, dp(4)))
        col.addView(pillBtn("End call", RED) { endCall() }, llp(MATCH, dp(44), dp(12)))
        return col
    }

    // ---------------------------------------------------------------- charging

    private fun onPowerConnected() {
        handler.post {
            charging = true
            handler.removeCallbacks(chargeOff)
            handler.postDelayed(chargeOff, CHARGE_MS)
            render()
        }
    }

    fun demoCharge() = onPowerConnected()

    private fun batteryPercent(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return level * 100 / scale
    }

    private fun chargeCompact(): View {
        val f = FrameLayout(this)
        f.addView(tv("\u26A1", 15f, GREEN), lpf(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL, ms = dp(16)))
        f.addView(tv("${batteryPercent()}%", 13f, GREEN, true), lpf(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL, me = dp(14)))
        return f
    }

    // ---------------------------------------------------------------- notifications

    fun show(title: String, text: String) = postNotification(packageName, title, text, null)

    fun postNotification(pkg: String, title: String, text: String, pi: PendingIntent?) {
        handler.post {
            val pm = packageManager
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
            notifCounter++
            trayHidden = false
            notif = NotifData(notifCounter, pkg, label, title, text, pi)
            handler.removeCallbacks(notifOff)
            handler.postDelayed(notifOff, NOTIF_MS)
            render()
        }
    }

    private fun notifExpanded(): View {
        val n = notif
        val col = column()
        val top = FrameLayout(this)
        top.addView(appIcon(n?.pkg, dp(7)), lpf(dp(26), dp(26), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(tv(n?.label, 12f, GREY), lpf(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, llp(MATCH, cutH))
        col.addView(tv(n?.title, 16f, Color.WHITE, true), llp(MATCH, WRAP, dp(6)))
        col.addView(tv(n?.text, 14f, 0xFFD0D0D4.toInt(), false, 2), llp(MATCH, WRAP, dp(2)))
        return col
    }

    // ---------------------------------------------------------------- second bubble (like iOS split island)

    private fun updateBubble() {
        val want = visibleOk && mediaVisible() && isPlaying() && kind != Kind.MEDIA && kind != Kind.NONE
        if (want) {
            val b = bubble ?: ImageView(this).also { iv ->
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(Color.BLACK)
                iv.clipToOutline = true
                iv.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
                }
                bubble = iv
            }
            val a = mediaArt
            if (a != null) b.setImageBitmap(a)
            if (!bubbleOn) {
                val l = overlayParams(cutH, cutH, false)
                blp = l
                try {
                    wm.addView(b, l)
                    bubbleOn = true
                    b.scaleX = 0f; b.scaleY = 0f
                    b.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(SpringInterp()).start()
                } catch (_: Exception) {}
            }
            positionBubble()
        } else if (bubbleOn) {
            val b = bubble
            if (b != null) {
                b.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction { removeBubble() }.start()
            }
        }
    }

    private fun positionBubble() {
        val l = blp ?: return
        val b = bubble ?: return
        if (!bubbleOn) return
        l.x = lp.width / 2 + dp(6) + l.width / 2
        l.y = cutTop
        try { wm.updateViewLayout(b, l) } catch (_: Exception) {}
    }

    private fun removeBubble() {
        val b = bubble
        if (bubbleOn && b != null) {
            try { wm.removeView(b) } catch (_: Exception) {}
        }
        bubbleOn = false
    }
}

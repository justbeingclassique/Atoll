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
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
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
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
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

/** The island body: glossy solid black when small, frosted liquid glass when expanded. */
class PillView(ctx: Context) : FrameLayout(ctx) {
    var radius = 0f
    var glass = 0f
        set(v) { field = v; invalidate() }
    var tint = 0
        set(v) { field = v; invalidate() }
    var blurOn = false
        set(v) { field = v; invalidate() }
    var frost = 0.8f
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF }
    private val r = RectF()

    init {
        setWillNotDraw(false)
        // fine frosted-glass grain
        val rnd = java.util.Random(7)
        val px = IntArray(96 * 96) { val v = rnd.nextInt(256); Color.argb(255, v, v, v) }
        val bmp = Bitmap.createBitmap(px, 96, 96, Bitmap.Config.ARGB_8888)
        grain.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val g = glass.coerceIn(0f, 1f)
        val f = frost.coerceIn(0f, 1f)

        // base: solid black when small, see-through dark glass when big
        val glassAlpha = if (blurOn) (190 - 70 * f).toInt() else 235
        fill.color = Color.argb(
            (255 + (glassAlpha - 255) * g).toInt(),
            (8 + 14 * g).toInt(), (10 + 16 * g).toInt(), (16 + 22 * g).toInt()
        )
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, radius, radius, fill)

        // small-pill gloss (a little depth)
        if (g < 0.98f) {
            layer.shader = LinearGradient(0f, 0f, 0f, min(h, 30f * d), 0xFF34343E.toInt(), 0x0034343E, Shader.TileMode.CLAMP)
            layer.alpha = (255 * (1f - g)).toInt()
            c.drawRoundRect(r, radius, radius, layer)
        }

        if (g > 0.02f) {
            // frosty white veil + fine grain
            layer.shader = null
            layer.color = Color.argb(((14 + 41 * f) * (if (blurOn) 1f else 0.5f) * g).toInt(), 255, 255, 255)
            c.drawRoundRect(r, radius, radius, layer)
            grain.alpha = (30 * f * g).toInt()
            c.drawRoundRect(r, radius, radius, grain)

            // diagonal sheen across the glass
            layer.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0x55FFFFFF, 0x0AFFFFFF, 0x22FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            layer.alpha = (255 * g).toInt()
            c.drawRoundRect(r, radius, radius, layer)

            // colour tint from whatever is playing or happening
            if (tint != 0) {
                val t = tint
                layer.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    Color.argb(0, Color.red(t), Color.green(t), Color.blue(t)),
                    Color.argb(95, Color.red(t), Color.green(t), Color.blue(t)),
                    Shader.TileMode.CLAMP
                )
                layer.alpha = (255 * g).toInt()
                c.drawRoundRect(r, radius, radius, layer)
            }

            // specular glow in the top-left corner
            layer.shader = RadialGradient(w * 0.15f, 0f, max(w, h) * 0.55f, 0x50FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            layer.alpha = (255 * g).toInt()
            c.drawRoundRect(r, radius, radius, layer)
        }

        // glass edge: bright top-left, soft middle, gentle bottom-right
        val sw = 1.3f * d
        edge.strokeWidth = sw
        edge.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xCCFFFFFF.toInt(), 0x1AFFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        edge.alpha = (255 * (0.45f + 0.55f * g)).toInt()
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        c.drawRoundRect(r, max(radius - sw / 2, 0f), max(radius - sw / 2, 0f), edge)

        // faint inner edge
        if (g > 0.02f) {
            edge.shader = null
            edge.strokeWidth = d
            edge.color = Color.argb((30 * g).toInt(), 255, 255, 255)
            r.set(sw + d, sw + d, w - sw - d, h - sw - d)
            c.drawRoundRect(r, max(radius - sw - d, 0f), max(radius - sw - d, 0f), edge)
        }

        // soft specular streak on the small pill
        if (g < 0.5f && h < 80f * d) {
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
        const val Y_NUDGE = 0           // dp: move the whole island down (+) or up (-) if it is not lined up
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
    private var blurApplied = false
    private var blurSupported = false
    private var offX = 0
    private var offY = 0
    private var offW = 0
    private var frost = 0.8f
    private var contactName: String? = null
    private var callerNumber: String? = null
    private var callerPhoto: Bitmap? = null
    private var phoneReceiverOn = false

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

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val n = i?.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!n.isNullOrEmpty() && n != callerNumber) {
                callerNumber = n
                lookupContact(n)
            }
        }
    }

    private val chargeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { onPowerConnected() }
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
    private fun idleW() = max(cutW + dp(10), cutW + mm(SIDE_MM) * 2 + offW)
    private fun baseY() = (cutTop + offY).coerceAtLeast(0)
    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }
    // expanded card hangs just under the normal status bar (and clear of the camera)
    private fun expandedTop() = max(statusBarH(), cutTop + cutH) + dp(6) + offY

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
        loadPrefs()
        readCutout()
        buildPill()
        registerReceiver(chargeReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        refreshMedia()
        registerCall()
        render(false)
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
            lp.y = baseY()
            contentKey = ""
            render(false)
        }
        updateBubble()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        artAnim?.cancel()
        try { unregisterReceiver(chargeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        try { controller?.unregisterCallback(mediaCb) } catch (_: Exception) {}
        try {
            sessionsListener?.let { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(it) }
        } catch (_: Exception) {}
        try { telCb?.let { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) } } catch (_: Exception) {}
        try { wm.removeView(pill) } catch (_: Exception) {}
        removeBubble()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- user adjustments

    private fun loadPrefs() {
        val p = getSharedPreferences("atoll", MODE_PRIVATE)
        offX = p.getInt("x", 0)
        offY = p.getInt("y", 0)
        offW = p.getInt("w", 0)
        frost = p.getInt("frost", 80) / 100f
    }

    /** Called by the app whenever a slider moves. */
    fun applyPrefs() {
        handler.post {
            loadPrefs()
            lp.x = offX
            pill.frost = frost
            if (blurApplied) lp.blurBehindRadius = blurRadius()
            contentKey = ""
            render(false)
        }
    }

    private fun blurRadius() = dp(10) + (frost * dp(40)).toInt()

    // ---------------------------------------------------------------- window

    private fun readCutout() {
        var hole: RectF? = null
        try {
            val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val cut = d?.cutout
            // best source: the exact shape of the camera hole
            val path = cut?.cutoutPath
            if (path != null) {
                val b = RectF()
                path.computeBounds(b, true)
                if (b.width() > 0f && b.height() > 0f && b.width() < dp(80)) hole = b
            }
            // fallback: the cutout box usually runs from the very top of the screen down to the
            // bottom of the camera, so assume a round hole sitting at the bottom of that box
            if (hole == null) {
                val rect = cut?.boundingRects?.firstOrNull()
                if (rect != null && rect.width() > 0) {
                    val hh = min(rect.height(), rect.width()).toFloat()
                    hole = RectF(rect.left.toFloat(), rect.bottom - hh, rect.right.toFloat(), rect.bottom.toFloat())
                }
            }
        } catch (_: Exception) {}
        val hb = hole
        if (hb != null) {
            cutW = hb.width().toInt() + dp(PAD * 2)
            cutH = hb.height().toInt() + dp(PAD * 2)
            cutTop = (hb.top.toInt() - dp(PAD) + dp(Y_NUDGE)).coerceAtLeast(0)
        } else {
            cutW = dp(30); cutH = dp(30); cutTop = dp(10) + dp(Y_NUDGE)
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
            x = offX
            y = baseY()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun buildPill() {
        lp = overlayParams(idleW(), cutH, true)
        curRadius = radiusFor(cutH)
        pill = PillView(this).apply {
            radius = curRadius
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, lp.width, lp.height, curRadius)
                }
            }
            setOnTouchListener { _, e -> onPillTouch(e) }
        }
        pill.frost = frost
        wm.addView(pill, lp)
    }

    private fun radiusFor(h: Int) = min(h / 2f, dp(14).toFloat())

    private fun setSize(w: Int, h: Int, y: Int) {
        lp.width = w.coerceAtLeast(idleW())
        lp.height = h.coerceAtLeast(cutH)
        lp.y = y.coerceAtLeast(0)
        curRadius = radiusFor(lp.height)
        pill.radius = curRadius
        pill.glass = ((lp.height - cutH).toFloat() / dp(70)).coerceIn(0f, 1f)
        val wantBlur = blurSupported && lp.height > cutH + dp(50)
        if (wantBlur != blurApplied) {
            blurApplied = wantBlur
            if (wantBlur) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = blurRadius()
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
            pill.blurOn = wantBlur
        }
        try { wm.updateViewLayout(pill, lp) } catch (_: Exception) {}
        pill.invalidateOutline()
        positionBubble()
    }

    private fun animateTo(w: Int, h: Int, y: Int, animate: Boolean) {
        anim?.cancel()
        val sw = lp.width
        val sh = lp.height
        val sy = lp.y
        if (!animate || (sw == w && sh == h && sy == y)) { setSize(w, h, y); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = SpringInterp()
            addUpdateListener {
                val f = it.animatedValue as Float
                setSize((sw + (w - sw) * f).toInt(), (sh + (h - sh) * f).toInt(), (sy + (y - sy) * f).toInt())
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
        blurSupported = try { wm.isCrossWindowBlurEnabled } catch (_: Exception) { false }
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
        val key = "$k|$exp|${notif?.id}|$mediaTitle|$mediaArtist|${System.identityHashCode(mediaArt)}|$callerName|$demoCall|$contactName|$callerNumber|${System.identityHashCode(callerPhoto)}|$trayKey"
        if (key != contentKey) {
            contentKey = key
            val v: View? = if (k == Kind.NONE && !showTray()) null else buildContent(k, exp)
            swap(v, w, h, animate)
        }
        applyTint(k)
        animateTo(w, h, if (exp) expandedTop() else baseY(), animate)
        updateMediaDynamic()
        updateBubble()

        handler.removeCallbacks(tick)
        if (k == Kind.MEDIA && exp) handler.post(tick)
        handler.removeCallbacks(autoCollapse)
        if (userExpanded) handler.postDelayed(autoCollapse, EXPAND_TIMEOUT)
    }

    private fun applyTint(k: Kind) {
        pill.tint = when {
            k == Kind.MEDIA && mediaArt != null -> accent
            k == Kind.CHARGE || k == Kind.CALL_RING || k == Kind.CALL_ACTIVE -> GREEN
            else -> 0
        }
    }

    private fun sizeFor(k: Kind, exp: Boolean): Pair<Int, Int> {
        val bigW = min(resources.displayMetrics.widthPixels - dp(24), dp(380))
        return when {
            k == Kind.NONE -> Pair(idleW(), cutH)
            !exp -> Pair(idleW() + dp(GROW), cutH)
            k == Kind.MEDIA -> Pair(bigW, dp(176))
            k == Kind.NOTIF -> Pair(bigW, dp(92))
            k == Kind.CALL_RING -> Pair(bigW, dp(138))
            else -> Pair(bigW, dp(138))
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
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val art = roundedImage(dp(10)); setArt(art)
        art.setOnClickListener { pop(it); openApp() }
        artRef = art
        head.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(mediaTitle, 16f, Color.WHITE, true))
        info.addView(tv(mediaArtist, 14f, GREY))
        head.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        val wave = WaveView(this).also { it.color = accent; it.active = isPlaying() }
        waveRef = wave
        head.addView(wave, LinearLayout.LayoutParams(dp(26), dp(20)))
        head.addView(appIcon(controller?.packageName, dp(5)), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
        col.addView(head, llp(MATCH, dp(52)))

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
            if (!phoneReceiverOn) {
                try {
                    registerReceiver(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
                    phoneReceiverOn = true
                } catch (_: Exception) {}
            }
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
            else -> {
                callState = 0
                callerName = null
                contactName = null
                callerNumber = null
                callerPhoto = null
            }
        }
        render()
    }

    private fun displayName(): String =
        contactName ?: callerName ?: prettyNumber(callerNumber) ?: "Unknown caller"

    private fun prettyNumber(n: String?): String? {
        if (n.isNullOrEmpty()) return null
        return try { PhoneNumberUtils.formatNumber(n, Locale.getDefault().country) ?: n } catch (_: Exception) { n }
    }

    /** Look the number up in the phone's contacts (name + photo). */
    private fun lookupContact(number: String) {
        Thread {
            var name: String? = null
            var photo: Bitmap? = null
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                val cols = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
                contentResolver.query(uri, cols, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        val pu = c.getString(1)
                        if (pu != null) {
                            contentResolver.openInputStream(Uri.parse(pu))?.use { st -> photo = BitmapFactory.decodeStream(st) }
                        }
                    }
                }
            } catch (_: Exception) {}
            val n = name
            val ph = photo
            handler.post { contactName = n; callerPhoto = ph; render() }
        }.start()
    }

    private fun avatar(): View {
        val photo = callerPhoto
        if (photo != null) {
            return ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
                }
                setImageBitmap(photo)
            }
        }
        val nm = displayName()
        val letter = nm.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val palette = intArrayOf(0xFF5B6EE1.toInt(), 0xFF3FA796.toInt(), 0xFFB5651D.toInt(), 0xFF9B59B6.toInt(), 0xFFC0504D.toInt())
        val circle = palette[(nm.hashCode() and 0x7FFFFFFF) % palette.size]
        return TextView(this).apply {
            text = letter
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circle) }
        }
    }

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

    private fun endDemo() {
        demoCall = false
        callState = 0
        contactName = null
        callerNumber = null
        callerPhoto = null
        render()
    }

    fun demoIncomingCall() {
        handler.post {
            demoCall = true
            contactName = "Test Caller"
            callerNumber = "+263771234567"
            callerPhoto = null
            callState = 1
            render()
            handler.postDelayed({ if (demoCall) endDemo() }, 8000)
        }
    }

    private fun callHead(sub: View?): View {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(avatar(), LinearLayout.LayoutParams(dp(48), dp(48)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (sub != null) info.addView(sub)
        info.addView(tv(displayName(), 19f, Color.WHITE, true))
        val num = if (contactName != null) prettyNumber(callerNumber) else null
        if (num != null) info.addView(tv(num, 13f, GREY))
        head.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        return head
    }

    private fun callRing(): View {
        val col = column()
        col.addView(callHead(tv("Incoming call", 12f, GREY)), llp(MATCH, dp(48)))
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
        col.addView(callHead(chrono(13f)), llp(MATCH, dp(48)))
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(appIcon(n?.pkg, dp(10)), LinearLayout.LayoutParams(dp(42), dp(42)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(n?.label?.uppercase(), 11f, GREY))
        info.addView(tv(n?.title, 15f, Color.WHITE, true))
        info.addView(tv(n?.text, 13f, 0xFFD0D0D4.toInt(), false, 2))
        row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        return row
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
        val half = (if (expanded) idleW() else lp.width) / 2
        l.x = offX + half + dp(6) + l.width / 2
        l.y = baseY()
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

package com.nigel.island

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val BG_TOP = 0xFF0B1020.toInt()
        const val BG_BOTTOM = 0xFF1B2547.toInt()
        const val AQUA = 0xFF7DE3D1.toInt()
        const val LAVENDER = 0xFFA99BFF.toInt()
        const val INK = 0xFF0B1020.toInt()
        const val MUTED = 0xFFA6B0CF.toInt()
        const val OK = 0xFF5CE08A.toInt()
        const val BLOB1 = 0x667DE3D1
        const val BLOB2 = 0x55A99BFF
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    private val refreshers = ArrayList<() -> Unit>()
    private var autoStarted = false
    private lateinit var statusLine: TextView
    private lateinit var cta: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun glass(r: Int) = GradientDrawable().apply {
        setColor(0x14FFFFFF)
        setStroke(dp(1), 0x2EFFFFFF)
        cornerRadius = dp(r).toFloat()
    }

    private fun label(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun bounce(v: View) {
        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(SpringInterp()).start()
        }.start()
    }

    private fun blob(color: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(160).toFloat()
            colors = intArrayOf(color, color and 0x00FFFFFF)
        }
    }

    // ------------------------------------------------------------ status checks

    private fun overlayOk() = Settings.canDrawOverlays(this)

    private fun listenerOk(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return s != null && s.contains(packageName)
    }

    private fun phoneOk() =
        checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission("android.permission.ANSWER_PHONE_CALLS") == PackageManager.PERMISSION_GRANTED

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun running() = IslandService.instance != null

    // ------------------------------------------------------------ building blocks

    private fun stepCard(emoji: String, title: String, sub: String, isDone: () -> Boolean, action: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = glass(16)
        }
        val badge = TextView(this).apply {
            text = emoji
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1FFFFFFF) }
        }
        card.addView(badge, LinearLayout.LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 16f, Color.WHITE, true))
        col.addView(label(sub, 13f, MUTED))
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        val chip = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        card.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
        val refresh: () -> Unit = {
            val done = isDone()
            chip.text = if (done) "\u2713  Done" else "Set up  \u203A"
            chip.setTextColor(if (done) OK else INK)
            chip.background = rounded(if (done) 0x265CE08A else AQUA, 20)
        }
        refreshers.add(refresh)
        refresh()
        card.setOnClickListener { bounce(it); action() }
        return card
    }

    private fun tryChip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(14), dp(8), dp(14))
        background = glass(14)
        setOnClickListener { bounce(it); onClick() }
    }

    private fun needIsland(block: (IslandService) -> Unit) {
        val s = IslandService.instance
        if (s == null) Toast.makeText(this, "Start Atoll first \uD83D\uDC46", Toast.LENGTH_SHORT).show() else block(s)
    }

    private fun toggleService() {
        if (running()) {
            stopService(Intent(this, IslandService::class.java))
        } else if (!overlayOk()) {
            Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_LONG).show()
            return
        } else {
            startForegroundService(Intent(this, IslandService::class.java))
        }
        cta.postDelayed({ refreshAll() }, 700)
    }

    private fun refreshAll() {
        for (r in refreshers) r()
        if (running()) {
            statusLine.text = "\u25CF  Running"
            statusLine.setTextColor(OK)
            cta.text = "Stop Atoll"
            cta.setTextColor(Color.WHITE)
            cta.background = glass(18)
        } else {
            statusLine.text = "\u25CF  Not running yet"
            statusLine.setTextColor(MUTED)
            cta.text = "Start Atoll"
            cta.setTextColor(INK)
            cta.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(AQUA, LAVENDER)).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
    }

    // ------------------------------------------------------------ screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG_TOP
        window.navigationBarColor = BG_BOTTOM
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        // hero: a live little replica of the island
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val preview = PillView(this).apply { radius = dp(18).toFloat() }
        val art = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(AQUA, LAVENDER)).apply {
                cornerRadius = dp(6).toFloat()
            }
        }
        preview.addView(art, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(1), 0x33FFFFFF) }
        }
        preview.addView(dot, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
        val wave = WaveView(this).also { it.color = AQUA }
        preview.addView(wave, FrameLayout.LayoutParams(dp(20), dp(16), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
        hero.addView(preview, LinearLayout.LayoutParams(dp(240), dp(36)))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { preview.translationY = -dp(7) * (it.animatedValue as Float) }
            start()
        }
        hero.addView(label("Atoll", 36f, Color.WHITE, true), lp(WRAP, WRAP, 22))
        hero.addView(label("Give your camera cutout a personality.", 14f, MUTED), lp(WRAP, WRAP, 2))
        statusLine = label("", 13f, MUTED, true)
        hero.addView(statusLine, lp(WRAP, WRAP, 12))
        col.addView(hero, lp(MATCH, WRAP))

        // setup
        col.addView(label("SET UP", 12f, AQUA, true).apply { letterSpacing = 0.12f }, lp(WRAP, WRAP, 28))
        col.addView(stepCard("\uD83E\uDEA7", "Show over other apps", "Lets the island float on screen", this::overlayOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, lp(MATCH, WRAP, 10))
        col.addView(stepCard("\uD83D\uDD14", "Notification access", "Greyed out? App info \u2192 \u22EE \u2192 Allow restricted settings", this::listenerOk) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, lp(MATCH, WRAP, 10))
        col.addView(stepCard("\uD83D\uDCDE", "Calls", "Lets you answer and end calls from the island", this::phoneOk) {
            requestPermissions(arrayOf("android.permission.READ_PHONE_STATE", "android.permission.ANSWER_PHONE_CALLS"), 2)
        }, lp(MATCH, WRAP, 10))
        col.addView(stepCard("\uD83D\uDD0B", "Keep it alive", "Set battery to unrestricted so Samsung doesn't kill it", this::batteryOk) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }, lp(MATCH, WRAP, 10))

        // big button
        cta = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { bounce(it); toggleService() }
        }
        col.addView(cta, lp(MATCH, WRAP, 22))

        // try it
        col.addView(label("TRY IT", 12f, AQUA, true).apply { letterSpacing = 0.12f }, lp(WRAP, WRAP, 30))
        val tryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tryRow.addView(tryChip("\uD83D\uDD14  Alert") { needIsland { it.show("Messages", "Hey! Your island is alive \u2728") } },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) })
        tryRow.addView(tryChip("\uD83D\uDCDE  Call") { needIsland { it.demoIncomingCall() } },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        tryRow.addView(tryChip("\u26A1  Charge") { needIsland { it.demoCharge() } },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
        col.addView(tryRow, lp(MATCH, WRAP, 10))

        // gestures
        col.addView(label("GESTURES", 12f, AQUA, true).apply { letterSpacing = 0.12f }, lp(WRAP, WRAP, 30))
        val tips = TextView(this).apply {
            text = "\uD83D\uDC46  Tap the pill  \u2192  play / pause\n" +
                "\u2194\uFE0F  Swipe left or right  \u2192  next / previous\n" +
                "\u270B  Long-press  \u2192  open the full player\n" +
                "\u2B06\uFE0F  Swipe up  \u2192  tuck it away"
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.35f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glass(16)
        }
        col.addView(tips, lp(MATCH, WRAP, 10))

        // backdrop: soft colour glows so the glass cards have something to refract
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
        }
        root.addView(blob(BLOB1), FrameLayout.LayoutParams(dp(320), dp(320), Gravity.TOP or Gravity.END).apply {
            topMargin = -dp(90); marginEnd = -dp(110)
        })
        root.addView(blob(BLOB2), FrameLayout.LayoutParams(dp(320), dp(320), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = -dp(100); marginStart = -dp(120)
        })
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(col) })
        setContentView(root)

        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        // little entrance animation
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(24).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(450)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (!autoStarted && overlayOk() && !running()) {
            autoStarted = true
            startForegroundService(Intent(this, IslandService::class.java))
        }
        cta.postDelayed({ refreshAll() }, 500)
        refreshAll()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        IslandService.instance?.registerCall()
        refreshAll()
    }
}

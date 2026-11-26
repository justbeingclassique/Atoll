package com.nigel.island

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_PHONE_STATE",
                "android.permission.ANSWER_PHONE_CALLS"
            ), 1
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        box.addView(TextView(this).apply {
            text = "Atoll"
            textSize = 30f
            setPadding(0, 0, 0, 24)
        })
        box.addView(TextView(this).apply {
            text = "Do steps 1 to 4 once. Then play music in any app, get a call, plug in your charger or receive a notification."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 32)
        })
        fun btn(label: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }
        fun needIsland(block: (IslandService) -> Unit) {
            val s = IslandService.instance
            if (s == null) Toast.makeText(this, "Do step 4 first", Toast.LENGTH_SHORT).show() else block(s)
        }
        btn("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("3. Battery: set to unrestricted") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        btn("4. Start my island") {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, IslandService::class.java))
            } else {
                Toast.makeText(this, "Do step 1 first", Toast.LENGTH_LONG).show()
            }
        }
        box.addView(TextView(this).apply {
            text = "Tests"
            textSize = 18f
            setPadding(0, 40, 0, 8)
        })
        btn("Test notification") { needIsland { it.show("Messages", "Hey! The island is working perfectly.") } }
        btn("Test incoming call") { needIsland { it.demoIncomingCall() } }
        btn("Test charging") { needIsland { it.demoCharge() } }
        btn("Stop island") { stopService(Intent(this, IslandService::class.java)) }

        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        IslandService.instance?.registerCall()
    }
}

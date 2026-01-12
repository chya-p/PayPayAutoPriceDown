package com.example.paypayautopricedown

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 値下げ開始
        findViewById<Button>(R.id.btnStartDiscount).setOnClickListener {
            Toast.makeText(this, "PayPayフリマで値下げ準備中…", Toast.LENGTH_SHORT).show()
        }

        // Accessibility 設定
        findViewById<Button>(R.id.btnAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "PriceDown Accessibility Service を有効にしてください",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

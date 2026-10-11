package com.wireless.audiostreamer.ui

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.util.AppPrefs

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        val group = findViewById<RadioGroup>(R.id.roleGroup)
        val swScreen = findViewById<SwitchCompat>(R.id.swScreenShare)

        when (AppPrefs.getRole(this)) {
            AppPrefs.ROLE_SENDER -> findViewById<RadioButton>(R.id.rbSender).isChecked = true
            AppPrefs.ROLE_RECEIVER -> findViewById<RadioButton>(R.id.rbReceiver).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbAuto).isChecked = true
        }
        swScreen.isChecked = AppPrefs.wantScreenShare(this)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val role = when (group.checkedRadioButtonId) {
                R.id.rbSender -> AppPrefs.ROLE_SENDER
                R.id.rbReceiver -> AppPrefs.ROLE_RECEIVER
                else -> AppPrefs.ROLE_AUTO
            }
            AppPrefs.setRole(this, role)
            AppPrefs.setWantScreenShare(this, swScreen.isChecked)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

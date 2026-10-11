package com.wireless.audiostreamer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.util.AppPrefs

/** First launch: pick Auto / Sender / Receiver once. */
class RoleSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppPrefs.isRoleChosen(this) && intent?.getBooleanExtra("force", false) != true) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_role_setup)

        findViewById<MaterialButton>(R.id.btnAuto).setOnClickListener { save(AppPrefs.ROLE_AUTO) }
        findViewById<MaterialButton>(R.id.btnSender).setOnClickListener { save(AppPrefs.ROLE_SENDER) }
        findViewById<MaterialButton>(R.id.btnReceiver).setOnClickListener { save(AppPrefs.ROLE_RECEIVER) }
    }

    private fun save(role: String) {
        AppPrefs.setRole(this, role)
        Toast.makeText(this, "Role: $role (Settings se badal sakte ho)", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

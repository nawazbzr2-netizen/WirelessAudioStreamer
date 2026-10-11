package com.wireless.audiostreamer.util

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val NAME = "was_prefs"
    private const val KEY_ROLE = "default_role"
    private const val KEY_ROLE_SET = "role_chosen"
    private const val KEY_SCREEN_SHARE = "want_screen_share"

    const val ROLE_AUTO = "AUTO"
    const val ROLE_SENDER = "SENDER"
    const val ROLE_RECEIVER = "RECEIVER"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isRoleChosen(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ROLE_SET, false)

    fun getRole(ctx: Context): String =
        prefs(ctx).getString(KEY_ROLE, ROLE_AUTO) ?: ROLE_AUTO

    fun setRole(ctx: Context, role: String) {
        prefs(ctx).edit()
            .putString(KEY_ROLE, role)
            .putBoolean(KEY_ROLE_SET, true)
            .apply()
    }

    fun wantScreenShare(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCREEN_SHARE, false)

    fun setWantScreenShare(ctx: Context, want: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SCREEN_SHARE, want).apply()
    }
}

package com.lasergrbl.android.platform

import android.content.Context
import com.lasergrbl.core.grbl.SettingsPersistence

/**
 * Android 侧设置持久化实现。
 *
 * v2 把设置写在 WebView 的 `localStorage`（键 `lasergrbl.settings`，值是 JSON）；
 * 原生侧用 SharedPreferences 承载**同一个键与同一段 JSON**，这样：
 *  1. 迁移逻辑（如果以后要从 v2 搬数据）只需要搬运字符串；
 *  2. `:core` 里的 `SettingsStore` / `DeviceProfiles` 不需要知道自己跑在哪个平台。
 */
class AndroidSettingsPersistence(context: Context) : SettingsPersistence {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        /** 与 v2 的 localStorage 同一个命名空间前缀。 */
        const val PREFS_NAME = "igrbl.localStorage"
    }
}

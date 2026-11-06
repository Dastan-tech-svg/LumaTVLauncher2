package com.lumatv.launcher

/** Pure classification helpers. No third-party dependencies or background services. */
internal object LuAppRules {
    private val videoNames = listOf("youtube", "netflix", "kino", "кино", "vlc", "ivi", "okko",
        "iptv", "megogo", "rutube", "видео", "video", "wink", "prime", "ivi.ru")
    fun isVideo(packageName: String, title: String): Boolean {
        val joined = "$packageName $title".lowercase(java.util.Locale.ROOT)
        return videoNames.any { joined.contains(it) }
    }
    fun filterLabel(label: String, search: String): Boolean =
        search.isBlank() || label.contains(search.trim(), ignoreCase = true)
}

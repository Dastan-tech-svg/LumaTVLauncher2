package com.lumatv.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Luma TV 7.0 / luDOS 1.0. Native Android launcher without manufacturer locks.
 * Three visual profiles: LITE, BALANCED, ULTRA transparent glass.
 * No Compose, third-party SDKs, ads, network service, or background process.
 */
class LumaHomeActivity : Activity() {
    private enum class Page { HOME, APPS, WALLPAPERS, SETTINGS, QUICK, WELCOME }
    private enum class PerformanceMode { LITE, BALANCED, ULTRA }
    private data class AppItem(val pkg: String, val label: String, val icon: Drawable, val launch: Intent)

    private val pref by lazy { getSharedPreferences("ludos_v5", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var tintOverlay: View
    private lateinit var shell: FrameLayout
    private lateinit var ambientClock: TextView
    private var page: Page = Page.HOME
    private var wizardStep = 0
    private var allAppsCache: List<AppItem>? = null
    private var lastAppsLoad = 0L
    private var ambient = false
    private var homeClock: TextView? = null
    private var heroClock: TextView? = null
    private var blurCache: Bitmap? = null
    private var blurredWallpaperIndex = Int.MIN_VALUE
    private var lastFocusedPackage: String? = null
    private var mainFocus: View? = null
    private val accents = intArrayOf(0xFF8BE7FF.toInt(), 0xFFC5A4FF.toInt(), 0xFF84F2CC.toInt(), 0xFFFFD18F.toInt())
    private val accentLabels = arrayOf("Арктический", "Аметист", "Мята", "Солнце")
    private val wallpaperNames = arrayOf("Ночной олень", "Орёл в горах", "Лесная лиса", "Закат и лошади", "Полярный волк")
    private val wallpaperIds = intArrayOf(R.drawable.wallpaper_deer, R.drawable.wallpaper_eagle,
        R.drawable.wallpaper_fox, R.drawable.wallpaper_horses, R.drawable.wallpaper_wolf)
    private val wallpaperThumbs = intArrayOf(R.drawable.thumb_deer, R.drawable.thumb_eagle,
        R.drawable.thumb_fox, R.drawable.thumb_horses, R.drawable.thumb_wolf)

    private val showAmbient = Runnable {
        if (page == Page.HOME && !ambient) {
            ambient = true
            shell.animate().alpha(0f).setDuration(700).start()
            ambientClock.text = clockText() + "\nLuma TV"
            ambientClock.visibility = View.VISIBLE
            ambientClock.animate().alpha(1f).setDuration(700).start()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            homeClock?.text = clockText()
            heroClock?.text = clockText()
            if (ambient) ambientClock.text = clockText() + "\nLuma TV"
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        fullscreen()
        root = FrameLayout(this).apply {
            setBackgroundColor(0xFF030C1B.toInt())
            clipChildren = false
            clipToPadding = false
        }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        tintOverlay = View(this)
        root.addView(tintOverlay, FrameLayout.LayoutParams(-1, -1))
        shell = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        ambientClock = label("", 54f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.GONE
        }
        root.addView(ambientClock, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // Wallpaper rotation occurs only on a fresh process launch, not on every return from an app.
        if (pref.getBoolean("setup_done", false) && pref.getBoolean("wallpaper_auto", false)) {
            val current = pref.getInt("wallpaper", 0)
            if (current >= 0) pref.edit().putInt("wallpaper", (current + 1) % wallpaperIds.size).apply()
        }
        refreshBackground()
        applyVisualMode()
        if (!pref.getBoolean("setup_done", false)) {
            wizardStep = 0
            render(Page.WELCOME)
        } else {
            render(Page.HOME)
        }
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        fullscreen()
        wakeAmbient()
        if (page == Page.HOME) resetAmbientTimer()
    }

    override fun onPause() {
        handler.removeCallbacks(showAmbient)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(showAmbient)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ambient && event.action == KeyEvent.ACTION_DOWN) {
            wakeAmbient()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) resetAmbientTimer()
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Keep BACK behavior compatible with Android 9 TV remotes")
    override fun onBackPressed() {
        when (page) {
            Page.HOME -> { if (ambient) wakeAmbient() else Toast.makeText(this, "Luma TV — главный экран", Toast.LENGTH_SHORT).show() }
            Page.WELCOME -> if (wizardStep > 0) { wizardStep--; render(Page.WELCOME) } else render(Page.HOME)
            else -> render(Page.HOME)
        }
    }

    private fun fullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun screenDp(): Int = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
    private fun accent(): Int = accents[pref.getInt("accent", 0).coerceIn(0, accents.lastIndex)]
    /** Read old v5 settings once without resetting the user's preferences. */
    private fun performanceMode(): PerformanceMode {
        return when (pref.getString("performance_mode", null)) {
            "lite" -> PerformanceMode.LITE
            "balanced" -> PerformanceMode.BALANCED
            "ultra" -> PerformanceMode.ULTRA
            else -> if (pref.getBoolean("fast", true)) PerformanceMode.LITE else PerformanceMode.BALANCED
        }
    }
    private fun performanceName() = when (performanceMode()) {
        PerformanceMode.LITE -> "LITE"
        PerformanceMode.BALANCED -> "BALANCED"
        PerformanceMode.ULTRA -> "ULTRA"
    }
    private fun setPerformanceMode(mode: PerformanceMode) {
        pref.edit().putString("performance_mode", mode.name.lowercase(Locale.ROOT)).apply()
        applyVisualMode()
        render(page)
    }
    private fun applyVisualMode() {
        if (!::tintOverlay.isInitialized) return
        // Let the wallpaper remain visible through luGlass on every Android version.
        // Ultra is near-clear; Lite provides stronger contrast for low-end displays.
        val shades = when (performanceMode()) {
            PerformanceMode.LITE -> intArrayOf(0xA2081731.toInt(), 0x76010D22, 0xB9071125.toInt())
            PerformanceMode.BALANCED -> intArrayOf(0x74081731, 0x45010D22, 0x93071125.toInt())
            PerformanceMode.ULTRA -> intArrayOf(0x4C081731, 0x16010D22, 0x5C071125)
        }
        tintOverlay.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, shades)
    }
    private fun clockText() = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date())
    private fun dateText() = SimpleDateFormat("EEEE, d MMMM", Locale("ru", "RU")).format(Date())

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            setSingleLine(false)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    /**
     * luGlass 7: light native drawables, no native blur SDK or third-party renderer.
     * The ULTRA fill is deliberately close to transparent, showing the actual
     * wallpaper below it. Dialogs remain dark enough to read against bright photos.
     */
    private fun glass(selected: Boolean = false, radius: Float = 24f, opaque: Boolean = false): Drawable {
        val mode = performanceMode()
        val colors = when {
            opaque -> when (mode) {
                PerformanceMode.LITE -> intArrayOf(0xEE233A54.toInt(), 0xF00A1A32.toInt())
                PerformanceMode.BALANCED -> intArrayOf(0xD3294867.toInt(), 0xE30C1A33.toInt())
                PerformanceMode.ULTRA -> intArrayOf(0xA429496D.toInt(), 0xBE08172D.toInt())
            }
            selected -> when (mode) {
                PerformanceMode.LITE -> intArrayOf(colorWithAlpha(accent(), 130), 0xA41C3A60.toInt(), 0x8A07152D.toInt())
                PerformanceMode.BALANCED -> intArrayOf(colorWithAlpha(accent(), 72), 0x50243A62, 0x3A07152D)
                PerformanceMode.ULTRA -> intArrayOf(colorWithAlpha(accent(), 27), 0x1BFFFFFF, 0x1108172B)
            }
            else -> when (mode) {
                PerformanceMode.LITE -> intArrayOf(0x9E54718C.toInt(), 0x8A1F3B56.toInt(), 0x92071832.toInt())
                PerformanceMode.BALANCED -> intArrayOf(0x57577997, 0x38203955, 0x47091C34)
                PerformanceMode.ULTRA -> intArrayOf(0x1AFFFFFF, 0x100D2642, 0x08081930)
            }
        }
        val rounded = dp(radius.toInt()).toFloat()
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = rounded
            setStroke(dp(if (selected) 2 else 1), if (selected) accent() else when (mode) {
                PerformanceMode.LITE -> 0x94E0F4FF.toInt()
                PerformanceMode.BALANCED -> 0xB8E0F4FF.toInt()
                PerformanceMode.ULTRA -> 0xD8E5F8FF.toInt()
            })
        }
        if (mode == PerformanceMode.LITE) return base
        // A thin specular highlight makes the transparent surface look like glass.
        // Rendered by Android's native drawable system, not by a WebView.
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (mode == PerformanceMode.ULTRA)
                intArrayOf(0x32FFFFFF, 0x0DFFFFFF, 0x00FFFFFF)
            else intArrayOf(0x1FFFFFFF, 0x08FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = rounded
        }
        return LayerDrawable(arrayOf(base, shine))
    }
    private fun colorWithAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** Cached 320x180 wallpaper frosting is calculated once per wallpaper.
     * Unlike RenderEffect (Android 12+), this works on Android 5+ too.
     * ULTRA is opt-in. No background processing loop is used.
     */
    private fun ultraBlurredWallpaper(): Bitmap? {
        if (performanceMode() != PerformanceMode.ULTRA) return null
        val wallpaper = pref.getInt("wallpaper", 0)
        if (blurredWallpaperIndex == wallpaper && blurCache != null) return blurCache
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 4
            }
            val bitmap = if (wallpaper == -1) {
                BitmapFactory.decodeFile(File(filesDir, "luma_wallpaper_custom.jpg").absolutePath, options)
            } else {
                BitmapFactory.decodeResource(resources, wallpaperIds[wallpaper.coerceIn(wallpaperIds.indices)], options)
            } ?: return null
            val resized = Bitmap.createScaledBitmap(bitmap, 320, 180, true)
            if (bitmap !== resized) bitmap.recycle()
            val result = frostedBitmap(resized, 6)
            if (resized !== result) resized.recycle()
            blurCache = result
            blurredWallpaperIndex = wallpaper
            result
        } catch (_: Exception) { null } // Unsupported decoder/low memory -> gradient fallback.
    }

    /** Two-pass bounded box blur; ~230 KiB per cached result, no RenderScript dependency. */
    private fun frostedBitmap(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        val intermediate = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val diameter = radius * 2 + 1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var red = 0; var green = 0; var blue = 0
                for (offset in -radius..radius) {
                    val value = pixels[base + (x + offset).coerceIn(0, w - 1)]
                    red += (value ushr 16) and 255
                    green += (value ushr 8) and 255
                    blue += value and 255
                }
                intermediate[base + x] = (0xFF shl 24) or ((red / diameter) shl 16) or
                    ((green / diameter) shl 8) or (blue / diameter)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var red = 0; var green = 0; var blue = 0
                for (offset in -radius..radius) {
                    val value = intermediate[(y + offset).coerceIn(0, h - 1) * w + x]
                    red += (value ushr 16) and 255
                    green += (value ushr 8) and 255
                    blue += value and 255
                }
                output[y * w + x] = (0xFF shl 24) or ((red / diameter) shl 16) or
                    ((green / diameter) shl 8) or (blue / diameter)
            }
        }
        return Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun focusGlass(owner: View, selected: Boolean, radius: Float): Drawable {
        return if (performanceMode() == PerformanceMode.ULTRA) {
            LuGlassDrawable(owner, dp(radius.toInt()).toFloat(), selected, accent(), ultraBlurredWallpaper())
        } else glass(selected, radius)
    }

    /**
     * luDOS 7.0: focus scale must be allowed to extend beyond a tile's original rectangle.
     * All ancestor rows/scroll containers use clipChildren=false and reserve safe gutters.
     */
    private fun focusableGlass(view: View, radius: Float = 22f, selected: Boolean = false,
                               focusScale: Float = 1.04f, action: () -> Unit) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        view.isLongClickable = false
        view.background = focusGlass(view, selected, radius)
        view.setOnClickListener { action() }
        view.setOnFocusChangeListener { v, gained ->
            v.animate().cancel()  // Don't let a previous focus animation keep scaling after focus moves.
            v.background = focusGlass(v, gained || selected, radius)
            v.elevation = dp(if (gained) 14 else 0).toFloat()
            when (performanceMode()) {
                PerformanceMode.LITE -> {
                    // No scale animation on devices that need maximum responsiveness.
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
                PerformanceMode.BALANCED, PerformanceMode.ULTRA -> {
                    // Reserve space around every row so focus never cuts card corners.
                    val factor = if (performanceMode() == PerformanceMode.ULTRA) 1.0f else 0.6f
                    val zoom = 1f + (focusScale - 1f) * factor
                    val scale = if (gained) zoom else 1f
                    val duration = if (performanceMode() == PerformanceMode.ULTRA) 255L else 130L
                    v.animate().scaleX(scale).scaleY(scale)
                        .translationZ(if (gained) dp(11).toFloat() else 0f)
                        .setDuration(duration).setInterpolator(DecelerateInterpolator()).start()
                }
            }
        }
    }

    // Important for Android TV: clipping by even one parent cuts the focus glow/zoom.
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private fun space(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spacing(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun pageContent(): LinearLayout {
        shell.removeAllViews()
        shell.alpha = 1f
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Keep the glow away from the physical display edges and TV overscan.
            setPadding(dp(46), dp(32), dp(46), dp(46))
        }
        val body = column()
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        shell.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return body
    }

    private fun render(next: Page) {
        page = next
        mainFocus = null
        homeClock = null
        heroClock = null
        wakeAmbient()
        when (next) {
            Page.HOME -> home()
            Page.APPS -> allApps()
            Page.WALLPAPERS -> wallpaperPage()
            Page.SETTINGS -> settingsPage()
            Page.QUICK -> quickPanel()
            Page.WELCOME -> welcome()
        }
        resetAmbientTimer()
        mainFocus?.let { focus -> focus.post { if (focus.isAttachedToWindow) focus.requestFocus() } }
    }

    private fun resetAmbientTimer() {
        handler.removeCallbacks(showAmbient)
        if (page == Page.HOME && pref.getBoolean("ambient", true)) handler.postDelayed(showAmbient, 90_000)
    }

    private fun wakeAmbient() {
        if (!ambient) return
        ambient = false
        ambientClock.animate().alpha(0f).setDuration(170).withEndAction { ambientClock.visibility = View.GONE }.start()
        shell.animate().alpha(1f).setDuration(200).start()
        resetAmbientTimer()
    }

    private fun header(body: LinearLayout, title: String = "", back: Boolean = false) {
        val heading = row()
        if (back) {
            val backButton = chip("‹  Назад") { render(Page.HOME) }
            heading.addView(backButton, LinearLayout.LayoutParams(dp(122), dp(45)))
            heading.addView(spacing(20))
        } else {
            val brand = ImageView(this).apply { setImageResource(R.drawable.app_icon); scaleType = ImageView.ScaleType.FIT_CENTER }
            heading.addView(brand, LinearLayout.LayoutParams(dp(43), dp(43)))
            heading.addView(spacing(12))
        }
        val mainName = label(if (title.isNotBlank()) title else "Luma TV", 24f, Color.WHITE, true)
        heading.addView(mainName, LinearLayout.LayoutParams(0, -2, 1f))
        if (!back) {
            homeClock = label(clockText(), 26f, Color.WHITE, true).also {
                heading.addView(it)
            }
            heading.addView(spacing(18))
            heading.addView(chip("◉  Сеть") { openSettings(Settings.ACTION_WIFI_SETTINGS) }, LinearLayout.LayoutParams(dp(105), dp(45)))
            heading.addView(spacing(10))
            heading.addView(chip("⚙") { render(Page.QUICK) }, LinearLayout.LayoutParams(dp(55), dp(45)))
        } else {
            heading.addView(label("luDOS 1.0", 14f, 0xFFC7D4EA.toInt()))
        }
        body.addView(heading, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(space(22))
    }

    private fun chip(caption: String, action: () -> Unit): TextView {
        val v = label(caption, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
        }
        focusableGlass(v, 17f, action = action)
        return v
    }

    private fun section(text: String): TextView = label(text, 21f, Color.WHITE, true).apply {
        setPadding(0, dp(8), 0, dp(15))
    }

    /**
     * Keeps a CENTER_CROP wallpaper preview INSIDE a rounded rectangle on Android 9.
     * Do not clip the outer focusable card, or its focus ring will be cut off.
     */
    private fun wallpaperPreview(drawableId: Int, radiusDp: Int = 14): ImageView =
        ImageView(this).apply {
            setImageResource(drawableId)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xFF101B31.toInt())
                cornerRadius = dp(radiusDp).toFloat()
            }
            clipToOutline = true
        }

    private fun listOfApps(force: Boolean = false): List<AppItem> {
        if (!force && allAppsCache != null) return allAppsCache!!
        val pm = packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        val filters = listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        for (intent in filters) {
            @Suppress("DEPRECATION") val options = pm.queryIntentActivities(intent, 0)
            for (ri in options) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == packageName) continue
                if (!found.containsKey(pkg)) found[pkg] = ri
            }
        }
        val items = found.mapNotNull { (pkg, ri) ->
            if (isServiceApp(pkg, ri)) return@mapNotNull null
            try {
                val explicit = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: explicit
                AppItem(pkg, ri.loadLabel(pm)?.toString()?.trim()?.ifBlank { pkg } ?: pkg,
                    ri.loadIcon(pm), launch)
            } catch (_: Exception) { null }
        }
        allAppsCache = items.sortedWith(compareBy<AppItem> { priority(it.pkg, it.label) }.thenBy { it.label.lowercase(Locale.ROOT) })
        lastAppsLoad = System.currentTimeMillis()
        return allAppsCache!!
    }

    private fun isServiceApp(pkg: String, ri: ResolveInfo): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        val internal = listOf("settings", "inputmethod", "systemui", "setupwizard", "tvlauncher", "launcher3",
            "packageinstaller", "permissioncontroller", "provision", "webview", "documentsui", "backdrop",
            "feedback", "partnercustomizer", "recommendations", "screensaver")
        if (internal.any { p.contains(it) }) return true
        // Non-TV service packages have no place on the launcher. Only a few useful
        // built-in launchable apps are exceptions, such as YouTube and Google Play.
        val a = ri.activityInfo?.applicationInfo ?: return true
        val system = (a.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (system && p.startsWith("com.android.") && !p.contains("browser")) return true
        return false
    }

    private fun priority(pkg: String, label: String): Int {
        val p = (pkg + " " + label).lowercase(Locale.ROOT)
        return when {
            p.contains("youtube") -> 0
            p.contains("vending") || p.contains("play марк") || p.contains("google play") -> 1
            p.contains("netflix") -> 2
            p.contains("ivi") -> 3
            p.contains("vlc") -> 4
            p.contains("browser") || p.contains("chrome") -> 5
            else -> 10
        }
    }

    private fun hidden(): Set<String> = pref.getString("hidden", "")!!.split('|').filter { it.isNotBlank() }.toSet()
    private fun recents(): MutableList<String> = pref.getString("recents", "")!!.split('|').filter { it.isNotBlank() }.toMutableList()
    private fun saveRecents(items: List<String>) = pref.edit().putString("recents", items.distinct().take(12).joinToString("|")).apply()

    /**
     * luHome 7.0 Glass Update — cinematic but functional.
     * There are no fake movie suggestions, weather readings or dead buttons:
     * every glass card performs an action on the actual TV.
     */
    private fun home() {
        val body = pageContent()
        header(body)
        body.addView(space(4))

        // A scenic glass-covered hero plus a useful status panel replace the empty header.
        val landscape = landscapeHero()
        val status = todayPanel()
        if (screenDp() > 760) {
            val cards = row()
            cards.addView(landscape, LinearLayout.LayoutParams(0, dp(206), 1.65f))
            cards.addView(spacing(17))
            cards.addView(status, LinearLayout.LayoutParams(0, dp(206), 1f))
            body.addView(cards)
        } else {
            body.addView(landscape, LinearLayout.LayoutParams(-1, dp(172)))
            body.addView(space(12))
            body.addView(status, LinearLayout.LayoutParams(-1, dp(116)))
        }
        body.addView(space(21))

        body.addView(section("✦  Приложения"))
        val topApps = listOfApps().filter { !hidden().contains(it.pkg) }.take(5)
        val mainRow = row()
        mainFocus = null
        for (item in topApps) {
            val tile = appTile(item, featured = true)
            if (mainFocus == null) mainFocus = tile
            mainRow.addView(tile, LinearLayout.LayoutParams(dp(186), dp(106)))
            mainRow.addView(spacing(14))
        }
        val allTile = actionTile("▦", "Все приложения", "Полная библиотека", dp(186)) { render(Page.APPS) }
        if (mainFocus == null) mainFocus = allTile
        mainRow.addView(allTile, LinearLayout.LayoutParams(dp(186), dp(106)))
        body.addView(hscroll(mainRow), LinearLayout.LayoutParams(-1, dp(139)))
        body.addView(space(8))

        // Recently launched applications only show if there is actual history.
        val apps = listOfApps()
        val recentList = recents().take(7).mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
            .filter { !hidden().contains(it.pkg) }
        if (recentList.isNotEmpty()) {
            body.addView(section("◷  Недавно открывали"))
            val recentRow = row()
            for (item in recentList) {
                recentRow.addView(appTile(item, false), LinearLayout.LayoutParams(dp(142), dp(89)))
                recentRow.addView(spacing(12))
            }
            body.addView(hscroll(recentRow), LinearLayout.LayoutParams(-1, dp(121)))
            body.addView(space(6))
        }

        // Wallpaper collection adds color, visual density and immediate personalization.
        val galleryHeader = row()
        galleryHeader.addView(section("✧  Атмосфера luNature"), LinearLayout.LayoutParams(0, -2, 1f))
        galleryHeader.addView(chip("Все обои  →") { render(Page.WALLPAPERS) },
            LinearLayout.LayoutParams(dp(142), dp(42)))
        body.addView(galleryHeader)
        val galleryRow = row()
        for (index in wallpaperIds.indices) {
            galleryRow.addView(miniWallpaper(index), LinearLayout.LayoutParams(dp(180), dp(113)))
            galleryRow.addView(spacing(14))
        }
        body.addView(hscroll(galleryRow), LinearLayout.LayoutParams(-1, dp(141)))
        body.addView(space(14))

        val quickRow = row()
        quickRow.addView(chip("✧  luControl") { render(Page.QUICK) }, LinearLayout.LayoutParams(dp(159), dp(48)))
        quickRow.addView(spacing(12))
        quickRow.addView(chip("⚙  Настройки") { render(Page.SETTINGS) }, LinearLayout.LayoutParams(dp(170), dp(48)))
        quickRow.addView(spacing(12))
        quickRow.addView(chip("⚡  " + performanceName()) { render(Page.SETTINGS) },
            LinearLayout.LayoutParams(dp(146), dp(48)))
        body.addView(quickRow)
        body.addView(space(25))
    }

    /** Large real wallpaper art with translucent text, acting as wallpaper selector. */
    private fun landscapeHero(): View {
        val index = pref.getInt("wallpaper", 0).coerceIn(wallpaperIds.indices)
        val card = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val cover = wallpaperPreview(wallpaperIds[index], 23)
        card.addView(cover, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xDC061325.toInt(), 0x7D07172B, 0x05000611)).apply {
                cornerRadius = dp(23).toFloat()
            }
            clipToOutline = true
        }
        card.addView(shade, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        val copy = column().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(14), dp(20), dp(14)) }
        copy.addView(label("LU•NATURE  /  КОЛЛЕКЦИЯ", 12f, 0xFFC9F5FF.toInt(), true))
        copy.addView(space(12))
        copy.addView(label(wallpaperNames[index], 29f, Color.WHITE, true).apply {
            maxLines = 2; setShadowLayer(3f, 0f, 2f, 0xA4000000.toInt())
        })
        copy.addView(space(13))
        copy.addView(label("Смотреть все пейзажи   →", 15f, 0xFFE7FAFF.toInt(), true))
        card.addView(copy, FrameLayout.LayoutParams(-1, -1))
        focusableGlass(card, 26f, focusScale = 1.020f) { render(Page.WALLPAPERS) }
        return card
    }

    /** A functional native dashboard card, not a simulated weather feed. */
    private fun todayPanel(): View {
        val status = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(25), dp(18), dp(20), dp(17))
        }
        status.addView(label("LU•DOS 1.0   /   " + performanceName(), 12f, accent(), true))
        status.addView(space(5))
        heroClock = label(clockText(), 47f, Color.WHITE, true).also { status.addView(it) }
        status.addView(space(2))
        status.addView(label(dateText(), 16f, 0xFFEAF4FF.toInt()))
        status.addView(space(15))
        status.addView(label("◈  Панель управления  →", 14f, 0xFFC9EDFF.toInt(), true))
        focusableGlass(status, 26f, focusScale = 1.025f) { render(Page.QUICK) }
        return status
    }

    /** Focus stays inside the picture card while its translucent ring expands. */
    private fun miniWallpaper(index: Int): View {
        val current = pref.getInt("wallpaper", 0) == index
        val panel = column().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(wallpaperPreview(wallpaperThumbs[index], 13), LinearLayout.LayoutParams(-1, dp(64)))
        panel.addView(space(7))
        panel.addView(label((if (current) "✓  " else "") + wallpaperNames[index], 12f,
            Color.WHITE, current).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        focusableGlass(panel, 20f, selected = current, focusScale = 1.018f) {
            pref.edit().putInt("wallpaper", index).apply()
            refreshBackground()
            render(Page.HOME)
        }
        return panel
    }

    private fun hscroll(content: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // luDOS 7.0: free space is required around the first/last focused tile and its shadow.
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(content)
    }

    private fun appTile(app: AppItem, featured: Boolean): View {
        val wrap = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(7))
        }
        val icon = ImageView(this).apply { setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon); scaleType = ImageView.ScaleType.FIT_CENTER }
        wrap.addView(icon, LinearLayout.LayoutParams(dp(if (featured) 46 else 36), dp(if (featured) 46 else 36)))
        wrap.addView(space(if (featured) 7 else 4))
        val name = label(app.label, if (featured) 15f else 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        wrap.addView(name, LinearLayout.LayoutParams(-1, -2))
        focusableGlass(wrap, 22f) { launchApp(app) }
        wrap.setOnLongClickListener { showAppActions(app); true }
        wrap.setOnKeyListener { view, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
                showAppActions(app); true
            } else false
        }
        return wrap
    }

    private fun actionTile(symbol: String, title: String, subtitle: String, width: Int, action: () -> Unit): View {
        val c = column().apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(7), dp(8), dp(7)) }
        c.addView(label(symbol, 27f, accent(), true).apply { gravity = Gravity.CENTER })
        c.addView(space(3))
        c.addView(label(title, 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        if (subtitle.isNotEmpty()) c.addView(label(subtitle, 11f, 0xFFD1E7FC.toInt()).apply { gravity = Gravity.CENTER })
        focusableGlass(c, 22f, action = action)
        return c
    }

    private fun launchApp(app: AppItem) {
        try {
            val r = recents()
            r.remove(app.pkg)
            r.add(0, app.pkg)
            saveRecents(r)
            lastFocusedPackage = app.pkg
            startActivity(app.launch)
        } catch (_: Exception) {
            Toast.makeText(this, "Приложение не удалось открыть", Toast.LENGTH_LONG).show()
        }
    }

    private fun allApps() {
        val body = pageContent()
        header(body, "Все приложения", back = true)
        val visible = listOfApps().filter { !hidden().contains(it.pkg) }
        val top = row()
        val info = label("${visible.size} приложений   •   Удерживай OK для действий", 15f, 0xFFE4EDFF.toInt())
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(chip("↻ Обновить") {
            listOfApps(force = true)
            render(Page.APPS)
        }, LinearLayout.LayoutParams(dp(144), dp(44)))
        top.addView(spacing(10))
        top.addView(chip("Вернуть скрытые") {
            pref.edit().remove("hidden").apply()
            render(Page.APPS)
        }, LinearLayout.LayoutParams(dp(180), dp(44)))
        body.addView(top)
        body.addView(space(15))
        val search = EditText(this).apply {
            hint = "Поиск приложений..."
            textSize = 17f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFAFBED0.toInt())
            setPadding(dp(18), 0, dp(16), 0)
            background = glass(radius = 18f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(space(20))
        val holder = column()
        body.addView(holder)
        val columns = max(3, min(6, (screenDp() - 90) / 160))
        fun redraw(term: String) {
            holder.removeAllViews()
            val list = visible.filter { term.isBlank() || it.label.contains(term, ignoreCase = true) }
            for (batch in list.chunked(columns)) {
                val r = row().apply { setPadding(dp(11), dp(9), dp(11), dp(9)) }
                batch.forEach {
                    r.addView(appTile(it, false), LinearLayout.LayoutParams(0, dp(99), 1f))
                    r.addView(spacing(10))
                }
                repeat(columns - batch.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); r.addView(spacing(10)) }
                holder.addView(r, LinearLayout.LayoutParams(-1, dp(120)))
            }
            if (list.isEmpty()) holder.addView(label("Ничего не найдено", 19f, 0xFFE0E8F6.toInt()))
        }
        redraw("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { redraw(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        mainFocus = holder.findFocus() ?: top.getChildAt(1)
    }

    private fun showAppActions(item: AppItem) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("◉ Скрыть из списка" to {
            val set = hidden().toMutableSet(); set.add(item.pkg)
            pref.edit().putString("hidden", set.joinToString("|")).apply(); render(page)
        })
        actions.add("ⓘ Информация о приложении" to {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${item.pkg}"))
        })
        customMenu(item.label, actions)
    }

    private fun customMenu(title: String, actions: List<Pair<String, () -> Unit>>) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val lay = FrameLayout(this).apply { setBackgroundColor(0xB5000817.toInt()) }
        val box = column().apply { setPadding(dp(23), dp(23), dp(23), dp(23)); background = glass(opaque = true) }
        box.addView(label(title, 22f, Color.WHITE, true))
        box.addView(space(16))
        var initial: View? = null
        for ((text, act) in actions) {
            val option = label(text, 16f, Color.WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }
            focusableGlass(option, 16f) { dialog.dismiss(); act() }
            box.addView(option, LinearLayout.LayoutParams(-1, dp(48)))
            box.addView(space(8))
            if (initial == null) initial = option
        }
        val params = FrameLayout.LayoutParams(dp(min(490, screenDp() - 120)), -2, Gravity.CENTER)
        lay.addView(box, params)
        dialog.setContentView(lay)
        lay.setOnClickListener { dialog.dismiss() }
        dialog.show()
        initial?.post { initial?.requestFocus() }
    }

    private fun wallpaperPage() {
        val body = pageContent()
        header(body, "Обои — luNature", back = true)
        body.addView(label("Выбери один из пяти пейзажей Luma TV.", 16f,
            0xFFE1E8F4.toInt()))
        body.addView(space(19))
        val selected = pref.getInt("wallpaper", 0)
        val countColumns = if (screenDp() > 950) 3 else 2
        var selectedCard: View? = null
        var firstCard: View? = null
        for (base in wallpaperIds.indices step countColumns) {
            // Gaps are INSIDE the row: a focused tile never needs to draw outside its slot.
            val r = row().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            for (index in base until min(base + countColumns, wallpaperIds.size)) {
                if (index > base) r.addView(spacing(15))
                val item = column().apply {
                    setPadding(dp(11), dp(10), dp(11), dp(12))
                    gravity = Gravity.TOP
                }
                item.addView(wallpaperPreview(wallpaperThumbs[index]),
                    LinearLayout.LayoutParams(-1, dp(124)))
                item.addView(space(9))
                item.addView(label((if (selected == index) "✓  " else "") + wallpaperNames[index],
                    16f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 2
                })
                // Keep the preview and focus glow neatly inside the visual gallery.
                focusableGlass(item, 22f, selected == index, focusScale = 1.012f) {
                    pref.edit().putInt("wallpaper", index).apply()
                    refreshBackground()
                    render(Page.WALLPAPERS)
                }
                r.addView(item, LinearLayout.LayoutParams(0, dp(190), 1f))
                if (index == 0) firstCard = item
                if (selected == index) selectedCard = item
            }
            body.addView(r, LinearLayout.LayoutParams(-1, dp(216)))
        }
        if (selected == -1) {
            body.addView(space(10))
            body.addView(label("Сохранены ранее установленные обои. Выбери пейзаж, чтобы заменить их.",
                15f, 0xFFB7FFE7.toInt()))
        }
        // Keep focus on the selected picture, or on the first builtin picture if
        // an older installation still has a custom background selected.
        mainFocus = selectedCard ?: firstCard
    }

    private fun refreshBackground() {
        if (!::backdrop.isInitialized) return
        // Wallpaper-dependent frost must be recreated after every background change.
        blurCache = null
        blurredWallpaperIndex = Int.MIN_VALUE
        val selected = pref.getInt("wallpaper", 0)
        if (selected == -1) {
            val f = File(filesDir, "luma_wallpaper_custom.jpg")
            if (f.exists()) {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bmp = BitmapFactory.decodeFile(f.absolutePath, options)
                if (bmp != null) { backdrop.setImageBitmap(bmp); return }
            }
        }
        backdrop.setImageResource(wallpaperIds[selected.coerceIn(wallpaperIds.indices)])
    }

    private fun settingsPage() {
        val body = pageContent()
        header(body, "luDOS — Настройки", back = true)
        body.addView(section("Тема luGlass"))
        val colorRow = row()
        for (i in accentLabels.indices) {
            val theme = chip((if (pref.getInt("accent", 0) == i) "✓ " else "") + accentLabels[i]) {
                pref.edit().putInt("accent", i).apply(); render(Page.SETTINGS)
            }
            colorRow.addView(theme, LinearLayout.LayoutParams(0, dp(54), 1f))
            colorRow.addView(spacing(10))
        }
        body.addView(colorRow)
        body.addView(space(18))
        body.addView(section("Режимы luGlass / Производительность"))
        val perf = row()
        val profiles = listOf(
            Triple(PerformanceMode.LITE, "LITE", "Для слабых ТВ"),
            Triple(PerformanceMode.BALANCED, "BALANCED", "Для средних ТВ"),
            Triple(PerformanceMode.ULTRA, "ULTRA", "Для мощных ТВ")
        )
        profiles.forEachIndexed { index, (mode, name, details) ->
            val txt = (if (performanceMode() == mode) "✓ " else "") + "$name\n$details"
            val button = chip(txt) { setPerformanceMode(mode) }.apply {
                textSize = 13f
                maxLines = 2
            }
            perf.addView(button, LinearLayout.LayoutParams(0, dp(67), 1f))
            if (index < profiles.lastIndex) perf.addView(spacing(12))
        }
        body.addView(perf)
        body.addView(space(9))
        body.addView(label("LITE — экономно; BALANCED — лёгкий стеклянный эффект; " +
            "ULTRA — фон с настоящим статическим размытием внутри карточек, " +
            "объёмные блики и световые контуры. Это не динамический эффект iOS.",
            13f, 0xFFE9F4FF.toInt()))
        body.addView(space(20))
        body.addView(section("Персонализация и система"))
        val buttons = row()
        buttons.addView(chip("▧  Выбрать обои") { render(Page.WALLPAPERS) }, LinearLayout.LayoutParams(0, dp(55), 1f))
        buttons.addView(spacing(11))
        buttons.addView(chip("◎  Главный экран по умолчанию") { chooseDefaultHome() }, LinearLayout.LayoutParams(0, dp(55), 1f))
        buttons.addView(spacing(11))
        buttons.addView(chip("✦  Повторить настройку") { wizardStep = 0; render(Page.WELCOME) }, LinearLayout.LayoutParams(0, dp(55), 1f))
        body.addView(buttons)
        body.addView(space(17))
        val ambientButton = chip("Заставка luAmbient: " + if (pref.getBoolean("ambient", true)) "ВКЛ" else "ВЫКЛ") {
            pref.edit().putBoolean("ambient", !pref.getBoolean("ambient", true)).apply()
            render(Page.SETTINGS)
        }
        body.addView(ambientButton, LinearLayout.LayoutParams(dp(380), dp(51)))
        body.addView(space(10))
        val rotation = chip("Автосмена пейзажей при запуске: " +
            if (pref.getBoolean("wallpaper_auto", false)) "ВКЛ" else "ВЫКЛ") {
            pref.edit().putBoolean("wallpaper_auto", !pref.getBoolean("wallpaper_auto", false)).apply()
            render(Page.SETTINGS)
        }
        body.addView(rotation, LinearLayout.LayoutParams(dp(430), dp(51)))
        body.addView(space(18))
        body.addView(label("Luma TV 7.0  •  Kotlin + Android SDK  •  luDOS 1.0\n" +
            "Для Android TV / Google TV и совместимых устройств. " +
            "ULTRA использует размытую копию выбранных обоев; без системного blur-behind.",
            14f, 0xFFD7E6F6.toInt()))
        mainFocus = perf.getChildAt(0)
    }

    private fun quickPanel() {
        val body = pageContent()
        header(body, "luControl — Быстрый доступ", back = true)
        body.addView(section("Система"))
        val buttonList = listOf(
            "◉  Wi-Fi / сеть" to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            "⚙  Настройки телевизора" to { openSettings(Settings.ACTION_SETTINGS) },
            "▧  Обои" to { render(Page.WALLPAPERS) },
            "✦  Настройки luDOS" to { render(Page.SETTINGS) },
            "▦  Все приложения" to { render(Page.APPS) }
        )
        for ((name, action) in buttonList) {
            body.addView(chip(name, action), LinearLayout.LayoutParams(-1, dp(55)))
            body.addView(space(9))
        }
        body.addView(space(15))
        body.addView(label("Android ${Build.VERSION.RELEASE}  •  luDOS 1.0  •  Luma TV 7.0",
            14f, 0xFFE3EBFF.toInt()))
        mainFocus = body.getChildAt(3)
    }

    private fun welcome() {
        val body = pageContent()
        header(body, "Добро пожаловать в luDOS", back = false)
        body.addView(space(8))
        val intro = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(20), dp(26), dp(20))
            background = glass(opaque = true)
        }
        val (title, sub) = when (wizardStep) {
            0 -> "Luma TV 7.0" to "Новый стеклянный главный экран для Android TV и Google TV."
            1 -> "Цвет твоего стекла" to "Выбери оттенок luGlass, который нравится именно тебе."
            2 -> "Обои, которые вдохновляют" to "Выбери один из пяти пейзажей Luma TV."
            3 -> "Только нужные приложения" to "YouTube и Play Маркет появятся первыми, если они установлены. Остальное можно менять."
            4 -> "Три режима luGlass" to "LITE для слабых, BALANCED для средних и ULTRA для мощных телевизоров."
            else -> "Почти готово!" to "Можно сделать Luma TV главным экраном — только если прошивка телевизора это разрешает."
        }
        intro.addView(label("ШАГ ${wizardStep + 1} ИЗ 6", 14f, accent(), true))
        intro.addView(space(14))
        intro.addView(label(title, 34f, Color.WHITE, true))
        intro.addView(space(11))
        intro.addView(label(sub, 18f, 0xFFE5EEFC.toInt()))
        body.addView(intro, LinearLayout.LayoutParams(-1, dp(175)))
        body.addView(space(23))
        when (wizardStep) {
            1 -> {
                val r = row()
                for (i in accentLabels.indices) {
                    r.addView(chip((if (pref.getInt("accent", 0) == i) "✓ " else "") + accentLabels[i]) {
                        pref.edit().putInt("accent", i).apply(); render(Page.WELCOME)
                    }, LinearLayout.LayoutParams(0, dp(57), 1f))
                    r.addView(spacing(12))
                }
                body.addView(r)
            }
            2 -> {
                val r = row()
                for (i in wallpaperIds.indices) {
                    val v = column().apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(8)) }
                    v.addView(wallpaperPreview(wallpaperThumbs[i], radiusDp = 11),
                        LinearLayout.LayoutParams(-1, dp(76)))
                    v.addView(space(5))
                    v.addView(label((if (pref.getInt("wallpaper", 0) == i) "✓ " else "") + wallpaperNames[i],
                        11f, Color.WHITE).apply { gravity = Gravity.CENTER })
                    focusableGlass(v, 16f, pref.getInt("wallpaper", 0) == i, focusScale = 1.01f) {
                        pref.edit().putInt("wallpaper", i).apply(); refreshBackground(); render(Page.WELCOME)
                    }
                    r.addView(v, LinearLayout.LayoutParams(0, dp(124), 1f))
                    r.addView(spacing(10))
                }
                body.addView(r)
            }
            3 -> {
                val r = row()
                for (a in listOfApps().take(5)) {
                    r.addView(appTile(a, false), LinearLayout.LayoutParams(0, dp(103), 1f)); r.addView(spacing(10))
                }
                body.addView(r)
                body.addView(space(7))
                body.addView(label("Для дополнительных действий удерживай OK на плитке приложения.", 14f,
                    0xFFE5EEFC.toInt()))
            }
            4 -> {
                val r = row()
                PerformanceMode.values().forEachIndexed { index, mode ->
                    val name = mode.name
                    r.addView(chip((if (performanceMode() == mode) "✓ " else "") + name) {
                        setPerformanceMode(mode)
                    }, LinearLayout.LayoutParams(0, dp(60), 1f))
                    if (index < 2) r.addView(spacing(14))
                }
                body.addView(r)
            }
            5 -> {
                body.addView(chip("◉  Открыть выбор главного экрана") {
                    pref.edit().putBoolean("setup_done", true).apply()
                    chooseDefaultHome()
                }, LinearLayout.LayoutParams(dp(370), dp(60)))
                body.addView(space(15))
                body.addView(label("Если телевизор не разрешает смену домашнего экрана, пропусти этот шаг.", 15f,
                    0xFFE3EBFF.toInt()))
            }
        }
        body.addView(space(23))
        val nav = row()
        if (wizardStep > 0) {
            nav.addView(chip("‹  Назад") { wizardStep--; render(Page.WELCOME) },
                LinearLayout.LayoutParams(dp(140), dp(54)))
            nav.addView(spacing(15))
        }
        val next = chip(if (wizardStep == 5) "Готово! Открыть Luma TV  →" else "Продолжить  →") {
            if (wizardStep < 5) { wizardStep++; render(Page.WELCOME) }
            else { pref.edit().putBoolean("setup_done", true).apply(); render(Page.HOME) }
        }
        nav.addView(next, LinearLayout.LayoutParams(dp(285), dp(54)))
        mainFocus = next
        body.addView(nav)
    }

    private fun chooseDefaultHome() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            Toast.makeText(this, "Если список доступен, выбери Luma TV", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            customMenu("Главный экран", listOf(
                "На этом устройстве системный выбор лаунчера недоступен" to { render(Page.HOME) },
                "Продолжить в Luma TV" to { render(Page.HOME) }
            ))
        }
    }

    private fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action).apply { if (data != null) this.data = data })
        } catch (_: Exception) {
            Toast.makeText(this, "Эта настройка недоступна на данной прошивке", Toast.LENGTH_LONG).show()
        }
    }
}

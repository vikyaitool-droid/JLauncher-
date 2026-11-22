package com.vikranth.jlauncher

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {

    companion object {
        const val NAME = "Dr. Vikranth"   // default greeting name (change in launcher settings)
        const val MAX_DOCK = 5
        const val REQ_CAL = 7
        const val REQ_STORAGE = 8
        const val REQ_CONTACTS = 9
        const val ICON_PX = 112
        const val DEFAULT_CITY = "Bengaluru"
        val ACCENTS = intArrayOf(
            Color.rgb(226, 196, 141), // gold
            Color.rgb(140, 200, 255), // ice
            Color.rgb(255, 150, 170), // rose
            Color.rgb(120, 225, 180)  // mint
        )
        val WEATHER_PKGS = listOf(
            "com.sec.android.daemonapp",
            "com.samsung.android.weather",
            "com.google.android.apps.weather",
            "com.miui.weather2",
            "com.oneplus.weather"
        )
        const val G_SEARCH = 0
        const val G_GEAR = 1
        const val G_PIN = 2
        const val G_EYE = 3
        const val G_INFO = 4
        const val G_TRASH = 5
        const val G_CALC = 6
        const val G_PERSON = 7
        const val G_WEB = 8
    }

    data class App(val label: String, val pkg: String)
    data class Ev(val title: String, val whenText: String)
    data class Act(val label: String, val glyph: Int, val danger: Boolean, val go: () -> Unit)
    private class Holder(val icon: ImageView, val label: TextView)

    private var everything = listOf<App>()   // every launchable app
    private var all = listOf<App>()          // visible (not hidden) apps
    private val shown = mutableListOf<App>()
    private var dock = mutableListOf<String>()
    private val riMap = HashMap<String, ResolveInfo>()
    private val styled = HashMap<String, Drawable>()
    private val rawCache = HashMap<String, Drawable>()
    private var styling = false
    private val blank: Drawable = ColorDrawable(Color.TRANSPARENT)

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: BaseAdapter
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var dateTv: TextView
    private lateinit var greetDot: View
    private lateinit var greetHello: TextView
    private lateinit var greetName: TextView
    private lateinit var wxChip: LinearLayout
    private lateinit var wxIcon: TextView
    private lateinit var wxTemp: TextView
    private lateinit var eventBox: LinearLayout
    private lateinit var extraBox: LinearLayout
    private lateinit var dockBox: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private var sheetGlass: Glass? = null
    private var sheetOpen = false
    private var cityDirty = false

    private val glasses = ArrayList<Glass>()
    @Volatile private var glassBmp: Bitmap? = null
    private var wallId = -1
    private var realW = 0
    private var realH = 0
    private val letters = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val letterIndex = HashMap<Char, Int>()

    private lateinit var headDetector: GestureDetector

    private val tick = object : Runnable {
        override fun run() {
            updateGreeting()
            refreshEvents()
            fetchWeather(false)
            eventBox.postDelayed(this, 60000L)
        }
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val p = i?.data?.schemeSpecificPart
            if (p != null) {
                styled.remove(p)
                rawCache.remove(p)
            }
            loadApps()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density
    private fun pInt(k: String, d: Int) = prefs.getInt(k, d)
    private fun pBool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun accent() = ACCENTS[pInt("accent", 0).coerceIn(0, ACCENTS.size - 1)]
    private fun dimAlpha() = pInt("dim", 35) * 255 / 100

    private fun bg(c: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(c)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        return t
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun pressable(v: View) {
        val down = AnimatorSet()
        down.playTogether(ObjectAnimator.ofFloat(v, "scaleX", 0.9f), ObjectAnimator.ofFloat(v, "scaleY", 0.9f))
        down.duration = 90
        val up = AnimatorSet()
        up.playTogether(ObjectAnimator.ofFloat(v, "scaleX", 1f), ObjectAnimator.ofFloat(v, "scaleY", 1f))
        up.duration = 140
        val sla = StateListAnimator()
        sla.addState(intArrayOf(android.R.attr.state_pressed), down)
        sla.addState(intArrayOf(), up)
        v.stateListAnimator = sla
    }

    // ---------------------------------------------------------------- glass (frosted panel)

    private inner class Glass(private val host: View, private val radii: FloatArray, private val dark: Int) : Drawable() {
        private val path = Path()
        private val rect = RectF()
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val loc = IntArray(2)

        init {
            line.style = Paint.Style.STROKE
            line.strokeWidth = dpf(1f)
            val cm = ColorMatrix()
            cm.setSaturation(1.5f)
            bmpPaint.colorFilter = ColorMatrixColorFilter(cm)
        }

        override fun onBoundsChange(b: Rect) {
            rect.set(b)
            rect.inset(0.5f, 0.5f)
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            val h = b.height().toFloat().coerceAtLeast(1f)
            tint.shader = LinearGradient(0f, 0f, 0f, h,
                Color.argb(52, 255, 255, 255), Color.argb(14, 255, 255, 255), Shader.TileMode.CLAMP)
            line.shader = LinearGradient(0f, 0f, 0f, h,
                Color.argb(110, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP)
        }

        override fun draw(c: Canvas) {
            val bm = glassBmp
            if (bm != null && realW > 0) {
                host.getLocationInWindow(loc)
                c.save()
                c.clipPath(path)
                c.translate(-loc[0].toFloat(), -loc[1].toFloat())
                c.drawBitmap(bm, null, RectF(0f, 0f, realW.toFloat(), realH.toFloat()), bmpPaint)
                c.drawColor(Color.argb(dimAlpha(), 0, 0, 0))
                c.translate(loc[0].toFloat(), loc[1].toFloat())
                base.color = Color.argb(dark, 12, 16, 28)
                c.drawPath(path, base)
                c.restore()
            } else {
                base.color = Color.argb(dark + 90, 18, 24, 38)
                c.drawPath(path, base)
            }
            c.drawPath(path, tint)
            c.drawPath(path, line)
        }

        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun glass(v: View, radiusDp: Int, dark: Int = 70): Glass {
        val r = dpf(radiusDp.toFloat())
        val g = Glass(v, floatArrayOf(r, r, r, r, r, r, r, r), dark)
        v.background = g
        glasses.add(g)
        return g
    }

    private fun invalidateGlass() {
        for (g in glasses) g.invalidateSelf()
        window.decorView.invalidate()
    }

    private fun loadGlass(force: Boolean) {
        val wm = WallpaperManager.getInstance(this)
        val id = try { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) } catch (e: Exception) { -1 }
        if (!force && id == wallId && glassBmp != null) return
        wallId = id
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        realW = dm.widthPixels
        realH = dm.heightPixels
        Thread {
            try {
                val d = wm.drawable
                if (d != null) {
                    val tw = maxOf(24, realW / 10)
                    val th = maxOf(40, realH / 10)
                    val small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(small)
                    val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else realW
                    val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else realH
                    val sc = maxOf(tw.toFloat() / iw, th.toFloat() / ih)
                    cv.translate((tw - iw * sc) / 2f, (th - ih * sc) / 2f)
                    cv.scale(sc, sc)
                    d.setBounds(0, 0, iw, ih)
                    d.draw(cv)
                    for (n in 0 until 3) blur(small, 2)
                    glassBmp = small
                    runOnUiThread { invalidateGlass() }
                }
            } catch (e: SecurityException) {
                runOnUiThread {
                    if (Build.VERSION.SDK_INT in 27..32 &&
                        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                        !pBool("askedStorage", false)) {
                        prefs.edit().putBoolean("askedStorage", true).apply()
                        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
                    }
                }
            } catch (e: Throwable) {
                // no blur: glass falls back to a tinted panel
            }
        }.start()
    }

    private fun blur(bmp: Bitmap, r: Int) {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        boxPass(a, b, w, h, r, true)
        boxPass(b, a, w, h, r, false)
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = if (horiz) w else h
        val m = if (horiz) h else w
        val cnt = 2 * r + 1
        for (j in 0 until m) {
            for (i in 0 until n) {
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (k in -r..r) {
                    val ii = (i + k).coerceIn(0, n - 1)
                    val p = src[if (horiz) j * w + ii else ii * w + j]
                    sa += p ushr 24
                    sr += (p shr 16) and 255
                    sg += (p shr 8) and 255
                    sb += p and 255
                }
                dst[if (horiz) j * w + i else i * w + j] =
                    ((sa / cnt) shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }
    }

    // ---------------------------------------------------------------- icons (iOS-style squircle)

    private fun squirclePath(s: Float): Path {
        val p = Path()
        val n = 5.0
        val steps = 120
        for (i in 0..steps) {
            val t = i.toDouble() / steps * 2.0 * Math.PI
            val c = Math.cos(t)
            val si = Math.sin(t)
            val x = (0.5 + 0.5 * Math.signum(c) * Math.pow(Math.abs(c), 2.0 / n)).toFloat() * s
            val y = (0.5 + 0.5 * Math.signum(si) * Math.pow(Math.abs(si), 2.0 / n)).toFloat() * s
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun avgColor(d: Drawable): Int {
        return try {
            val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, 8, 8)
            d.draw(c)
            var r = 0
            var g = 0
            var bl = 0
            var n = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                val p = b.getPixel(x, y)
                if ((p ushr 24) > 128) {
                    r += Color.red(p)
                    g += Color.green(p)
                    bl += Color.blue(p)
                    n++
                }
            }
            if (n == 0) Color.rgb(44, 48, 62) else Color.rgb(r / n, g / n, bl / n)
        } catch (e: Exception) {
            Color.rgb(44, 48, 62)
        }
    }

    private fun styleIcon(d: Drawable): Bitmap {
        val s = ICON_PX
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = squirclePath(s.toFloat())
        c.save()
        c.clipPath(path)
        if (d is AdaptiveIconDrawable) {
            val full = (s * 108f / 72f).toInt()
            val off = (full - s) / 2
            d.background?.let {
                it.setBounds(-off, -off, s + off, s + off)
                it.draw(c)
            }
            d.foreground?.let {
                it.setBounds(-off, -off, s + off, s + off)
                it.draw(c)
            }
        } else {
            val avg = avgColor(d)
            val hsv = FloatArray(3)
            Color.colorToHSV(avg, hsv)
            val top = hsv.copyOf()
            top[2] = (top[2] * 1.1f).coerceAtMost(1f)
            val bot = hsv.copyOf()
            bot[2] = bot[2] * 0.75f
            val bp = Paint(Paint.ANTI_ALIAS_FLAG)
            bp.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), Color.HSVToColor(top), Color.HSVToColor(bot), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bp)
            val inset = (s * 0.06f).toInt()
            d.setBounds(inset, inset, s - inset, s - inset)
            d.draw(c)
        }
        val gp = Paint()
        gp.shader = LinearGradient(0f, 0f, 0f, s.toFloat(),
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(46, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), gp)
        c.restore()
        val ep = Paint(Paint.ANTI_ALIAS_FLAG)
        ep.style = Paint.Style.STROKE
        ep.strokeWidth = 1.5f
        ep.color = Color.argb(50, 255, 255, 255)
        c.drawPath(path, ep)
        return bmp
    }

    private fun iconOf(a: App): Drawable {
        if (pBool("ios", true)) {
            return styled[a.pkg] ?: blank
        }
        val cached = rawCache[a.pkg]
        if (cached != null) return cached
        val ri = riMap[a.pkg] ?: return blank
        val d = try { ri.loadIcon(packageManager) } catch (e: Exception) { blank }
        rawCache[a.pkg] = d
        return d
    }

    private fun styleMissing() {
        if (styling || !pBool("ios", true)) return
        val todo = everything.filter { !styled.containsKey(it.pkg) }
        if (todo.isEmpty()) return
        styling = true
        val pm = packageManager
        val snap = todo.mapNotNull { a -> riMap[a.pkg]?.let { ri -> Pair(a.pkg, ri) } }
        Thread {
            val out = HashMap<String, Drawable>()
            for ((pkg, ri) in snap) {
                try {
                    out[pkg] = BitmapDrawable(resources, styleIcon(ri.loadIcon(pm)))
                } catch (e: Throwable) {
                    // skip this icon
                }
            }
            runOnUiThread {
                styled.putAll(out)
                styling = false
                adapter.notifyDataSetChanged()
                refreshDock()
                if (sheetOpen) { /* header icon stays as drawn */ }
            }
        }.start()
    }

    // ---------------------------------------------------------------- small drawn glyphs

    private inner class Glyph(private val kind: Int, private var col: Int) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height) / 24f
            p.color = col
            p.strokeWidth = 1.8f
            c.save()
            c.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
            c.scale(s, s)
            when (kind) {
                G_SEARCH -> {
                    c.drawCircle(11f, 11f, 6.5f, p)
                    c.drawLine(16f, 16f, 21f, 21f, p)
                }
                G_GEAR -> {
                    c.drawCircle(12f, 12f, 3.2f, p)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val ca = Math.cos(a).toFloat()
                        val sa = Math.sin(a).toFloat()
                        c.drawLine(12f + ca * 6.5f, 12f + sa * 6.5f, 12f + ca * 9f, 12f + sa * 9f, p)
                    }
                }
                G_PIN -> {
                    val pa = Path()
                    pa.moveTo(9f, 4f); pa.lineTo(15f, 4f); pa.lineTo(14f, 10f); pa.lineTo(17f, 13f)
                    pa.lineTo(7f, 13f); pa.lineTo(10f, 10f); pa.close()
                    c.drawPath(pa, p)
                    c.drawLine(12f, 13f, 12f, 20f, p)
                }
                G_EYE -> {
                    val pa = Path()
                    pa.moveTo(2f, 12f); pa.quadTo(12f, 2f, 22f, 12f); pa.quadTo(12f, 22f, 2f, 12f)
                    c.drawPath(pa, p)
                    c.drawCircle(12f, 12f, 3f, p)
                }
                G_INFO -> {
                    c.drawCircle(12f, 12f, 9f, p)
                    c.drawLine(12f, 11f, 12f, 17f, p)
                    c.drawLine(12f, 7.5f, 12f, 7.6f, p)
                }
                G_TRASH -> {
                    c.drawLine(4f, 7f, 20f, 7f, p)
                    val a = Path()
                    a.moveTo(9f, 7f); a.lineTo(9f, 4f); a.lineTo(15f, 4f); a.lineTo(15f, 7f)
                    c.drawPath(a, p)
                    val b = Path()
                    b.moveTo(6f, 7f); b.lineTo(7f, 20f); b.lineTo(17f, 20f); b.lineTo(18f, 7f)
                    c.drawPath(b, p)
                }
                G_CALC -> {
                    c.drawRect(5f, 3f, 19f, 21f, p)
                    c.drawLine(8f, 8f, 16f, 8f, p)
                    c.drawLine(8f, 13f, 9f, 13f, p)
                    c.drawLine(12f, 13f, 13f, 13f, p)
                    c.drawLine(8f, 17f, 9f, 17f, p)
                    c.drawLine(12f, 17f, 16f, 17f, p)
                }
                G_PERSON -> {
                    c.drawCircle(12f, 8f, 4f, p)
                    val a = Path()
                    a.moveTo(4f, 20f); a.quadTo(12f, 9f, 20f, 20f)
                    c.drawPath(a, p)
                }
                else -> {
                    c.drawCircle(12f, 12f, 9f, p)
                    c.drawLine(3f, 12f, 21f, 12f, p)
                    c.drawLine(12f, 3f, 12f, 21f, p)
                }
            }
            c.restore()
        }
    }

    private inner class MaxScroll(private val maxH: Int) : ScrollView(this@MainActivity) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("jl", MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        initDock()
        buildUi()
        applySettings()
        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addAction(Intent.ACTION_PACKAGE_REPLACED)
        f.addDataScheme("package")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pkgReceiver, f)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { handleBack() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        search.setText("")
        loadGlass(false)
        loadApps()
        eventBox.removeCallbacks(tick)
        eventBox.post(tick)
    }

    override fun onPause() {
        super.onPause()
        eventBox.removeCallbacks(tick)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (sheetOpen) hideSheet()
        search.setText("")
        hideKeyboard()
        grid.smoothScrollToPosition(0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CAL -> refreshEvents()
            REQ_STORAGE -> if (ok) loadGlass(true)
            REQ_CONTACTS -> if (!ok) prefs.edit().putBoolean("contacts", false).apply()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    // Back (button or swipe gesture): close sheet -> clear search -> scroll to top -> stay home
    private fun handleBack(): Boolean {
        if (sheetOpen) { hideSheet(); return true }
        if (search.text.isNotEmpty()) { search.setText(""); hideKeyboard(); return true }
        if (search.hasFocus()) { hideKeyboard(); return true }
        if (grid.firstVisiblePosition > 0) { grid.smoothScrollToPosition(0); return true }
        return false
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun focusSearch() {
        search.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)
        root.fitsSystemWindows = true

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(dp(12), dp(2), dp(12), dp(8))
        main.isFocusable = true
        main.isFocusableInTouchMode = true

        // ---- header: date, big clock, greeting + weather chip
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL

        dateTv = tv("", 10.5f, accent())
        dateTv.letterSpacing = 0.25f
        dateTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        head.addView(dateTv, LinearLayout.LayoutParams(-2, -2))

        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 74f
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        clock.letterSpacing = -0.03f
        clock.includeFontPadding = false
        clock.setShadowLayer(dpf(10f), 0f, dpf(2f), Color.argb(90, 0, 0, 0))
        head.addView(clock, LinearLayout.LayoutParams(-2, -2))

        val greetRow = LinearLayout(this)
        greetRow.gravity = Gravity.CENTER
        val pill = LinearLayout(this)
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(14), dp(7), dp(14), dp(7))
        glass(pill, 20)
        greetDot = View(this)
        val dotLp = LinearLayout.LayoutParams(dp(5), dp(5))
        dotLp.rightMargin = dp(8)
        pill.addView(greetDot, dotLp)
        greetHello = tv("", 12f, Color.argb(225, 255, 255, 255))
        greetName = tv(NAME, 12f, Color.WHITE, true)
        greetName.setPadding(dp(4), 0, 0, 0)
        pill.addView(greetHello)
        pill.addView(greetName)
        greetRow.addView(pill, LinearLayout.LayoutParams(-2, -2))

        wxChip = LinearLayout(this)
        wxChip.gravity = Gravity.CENTER_VERTICAL
        wxChip.setPadding(dp(11), dp(7), dp(12), dp(7))
        glass(wxChip, 20)
        wxIcon = tv("", 13f, Color.WHITE)
        wxTemp = tv("", 13f, Color.WHITE, true)
        wxTemp.setPadding(dp(5), 0, 0, 0)
        wxChip.addView(wxIcon)
        wxChip.addView(wxTemp)
        wxChip.setOnClickListener { openWeather() }
        pressable(wxChip)
        val chipLp = LinearLayout.LayoutParams(-2, -2)
        chipLp.leftMargin = dp(8)
        greetRow.addView(wxChip, chipLp)
        val grLp = LinearLayout.LayoutParams(-2, -2)
        grLp.topMargin = dp(8)
        head.addView(greetRow, grLp)

        headDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onLongPress(e: MotionEvent) {
                haptic(head)
                showSettings()
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(36)) {
                    if (dy > 0) expandNotifications() else focusSearch()
                    return true
                }
                return false
            }
        })
        head.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            headDetector.onTouchEvent(e)
        }
        main.addView(head, LinearLayout.LayoutParams(-1, -2))

        // ---- today's events card
        eventBox = LinearLayout(this)
        eventBox.orientation = LinearLayout.VERTICAL
        eventBox.setPadding(dp(16), dp(12), dp(16), dp(12))
        glass(eventBox, 26)
        val evLp = LinearLayout.LayoutParams(-1, -2)
        evLp.topMargin = dp(10)
        main.addView(eventBox, evLp)

        // ---- search row (glass) + settings button
        val searchRow = LinearLayout(this)
        searchRow.gravity = Gravity.CENTER_VERTICAL
        glass(searchRow, 22)
        val sg = Glyph(G_SEARCH, Color.argb(200, 255, 255, 255))
        val sgLp = LinearLayout.LayoutParams(dp(18), dp(18))
        sgLp.leftMargin = dp(16)
        searchRow.addView(sg, sgLp)
        search = EditText(this)
        search.hint = "Search apps"
        search.setHintTextColor(Color.argb(160, 255, 255, 255))
        search.setTextColor(Color.WHITE)
        search.textSize = 14f
        search.isSingleLine = true
        search.background = null
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setPadding(dp(10), 0, dp(8), 0)
        search.setOnEditorActionListener { _, _, _ ->
            val q = search.text.toString().trim()
            if (q.isNotEmpty()) {
                if (shown.isNotEmpty()) launch(shown[0].pkg) else webSearch(q)
            }
            hideKeyboard()
            true
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        searchRow.addView(search, LinearLayout.LayoutParams(0, -1, 1f))
        val gear = Glyph(G_GEAR, Color.argb(220, 255, 255, 255))
        gear.setPadding(dp(4), dp(4), dp(4), dp(4))
        gear.setOnClickListener { showSettings() }
        pressable(gear)
        val gearLp = LinearLayout.LayoutParams(dp(40), dp(40))
        gearLp.rightMargin = dp(4)
        searchRow.addView(gear, gearLp)
        val srLp = LinearLayout.LayoutParams(-1, dp(44))
        srLp.topMargin = dp(10)
        main.addView(searchRow, srLp)

        // ---- search extras (calculator, contacts, web)
        extraBox = LinearLayout(this)
        extraBox.orientation = LinearLayout.VERTICAL
        extraBox.setPadding(dp(14), dp(4), dp(14), dp(4))
        extraBox.visibility = View.GONE
        glass(extraBox, 22)
        val exLp = LinearLayout.LayoutParams(-1, -2)
        exLp.topMargin = dp(8)
        main.addView(extraBox, exLp)

        // ---- app grid
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val a = shown[i]
                val h: Holder
                val cell: View
                if (v == null) {
                    val c = LinearLayout(this@MainActivity)
                    c.orientation = LinearLayout.VERTICAL
                    c.gravity = Gravity.CENTER_HORIZONTAL
                    c.setPadding(0, dp(7), 0, dp(5))
                    val ic = ImageView(this@MainActivity)
                    c.addView(ic, LinearLayout.LayoutParams(dp(50), dp(50)))
                    val lb = tv("", 10.5f, Color.WHITE)
                    lb.maxLines = 1
                    lb.ellipsize = TextUtils.TruncateAt.END
                    lb.gravity = Gravity.CENTER
                    lb.setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    lb.setPadding(dp(2), dp(4), dp(2), 0)
                    c.addView(lb, LinearLayout.LayoutParams(-1, -2))
                    pressable(c)
                    h = Holder(ic, lb)
                    c.tag = h
                    cell = c
                } else {
                    cell = v
                    h = v.tag as Holder
                }
                val cols = pInt("cols", 4).coerceIn(3, 5)
                val gridW = resources.displayMetrics.widthPixels - dp(24 + 60 + 6 + 14)
                val want = dp(intArrayOf(44, 50, 58)[pInt("icon", 1).coerceIn(0, 2)])
                val sz = minOf(want, gridW / cols - dp(10))
                val lp = h.icon.layoutParams
                lp.width = sz
                lp.height = sz
                h.icon.layoutParams = lp
                h.icon.setImageDrawable(iconOf(a))
                h.label.text = a.label
                h.label.visibility = if (pBool("labels", true)) View.VISIBLE else View.GONE
                return cell
            }
        }
        grid = GridView(this)
        grid.numColumns = 4
        grid.adapter = adapter
        grid.selector = bg(Color.argb(0, 255, 255, 255), 14)
        grid.isVerticalScrollBarEnabled = false
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        grid.isVerticalFadingEdgeEnabled = true
        grid.setFadingEdgeLength(dp(22))
        grid.setOnItemClickListener { _, _, i, _ -> launch(shown[i].pkg) }
        grid.setOnItemLongClickListener { v, _, i, _ ->
            haptic(v)
            showSheet(shown[i], false)
            true
        }

        // ---- A-Z fast scroller
        strip = LinearLayout(this)
        strip.orientation = LinearLayout.VERTICAL
        for (ch in letters) {
            val t = tv(ch.toString(), 8f, Color.argb(150, 255, 255, 255))
            t.gravity = Gravity.CENTER
            strip.addView(t, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        var lastLetter = -1
        strip.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) lastLetter = -1
            val idx = (e.y / v.height.toFloat() * letters.length).toInt().coerceIn(0, letters.length - 1)
            if (idx != lastLetter) {
                lastLetter = idx
                var pos = -1
                for (k in idx until letters.length) {
                    val f = letterIndex[letters[k]]
                    if (f != null) { pos = f; break }
                }
                if (pos >= 0) {
                    grid.setSelection(pos)
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            true
        }

        // ---- right dock (glass), top level with the grid
        dockBox = LinearLayout(this)
        dockBox.orientation = LinearLayout.VERTICAL
        dockBox.gravity = Gravity.CENTER_HORIZONTAL
        dockBox.setPadding(dp(6), dp(8), dp(6), dp(8))
        glass(dockBox, 30, 90)
        val dockWrap = FrameLayout(this)
        val dbLp = FrameLayout.LayoutParams(-1, -1)
        dbLp.bottomMargin = dp(6)
        dockWrap.addView(dockBox, dbLp)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        body.addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
        body.addView(strip, LinearLayout.LayoutParams(dp(14), -1))
        val dwLp = LinearLayout.LayoutParams(dp(60), -1)
        dwLp.leftMargin = dp(6)
        body.addView(dockWrap, dwLp)
        val bodyLp = LinearLayout.LayoutParams(-1, 0, 1f)
        bodyLp.topMargin = dp(8)
        main.addView(body, bodyLp)

        // ---- bottom sheet + scrim
        scrim = View(this)
        scrim.setBackgroundColor(Color.argb(130, 0, 0, 0))
        scrim.visibility = View.GONE
        scrim.setOnClickListener { hideSheet() }

        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true
        sheet.visibility = View.GONE
        sheetGlass = glass(sheet, 30, 120)

        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val shLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        shLp.setMargins(dp(8), 0, dp(8), dp(8))
        root.addView(sheet, shLp)
        setContentView(root)
        main.requestFocus()
    }

    // ---------------------------------------------------------------- settings

    private fun applySettings() {
        val n = prefs.getString("name", NAME)
        greetName.text = if (n == null) NAME else n
        grid.numColumns = pInt("cols", 4).coerceIn(3, 5)
        adapter.notifyDataSetChanged()
        eventBox.visibility = if (pBool("events", true)) View.VISIBLE else View.GONE
        wxChip.visibility = if (pBool("wx", true)) View.VISIBLE else View.GONE
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(accent())
        greetDot.background = dot
        dateTv.setTextColor(accent())
        updateGreeting()
        updateWeather()
        refreshEvents()
        applyBackground()
        invalidateGlass()
    }

    // dim always works; blur needs Android 12+ and blur effects enabled in the system
    private fun applyBackground() {
        window.setBackgroundDrawable(ColorDrawable(Color.argb(dimAlpha(), 0, 0, 0)))
        if (Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(dp(pInt("blur", 40) * 6 / 10))
        }
    }

    private fun showSettings() {
        sheet.removeAllViews()
        sheet.addView(sheetHeader("Launcher settings", null), LinearLayout.LayoutParams(-1, -2))

        val sv = MaxScroll((resources.displayMetrics.heightPixels * 0.62f).toInt())
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(24), dp(2), dp(24), dp(20))
        sv.addView(body, ViewGroup.LayoutParams(-1, -2))
        sheet.addView(sv, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("Your name"))
        val nameIn = EditText(this)
        val cur = prefs.getString("name", NAME)
        nameIn.setText(if (cur == null) NAME else cur)
        nameIn.setTextColor(Color.WHITE)
        nameIn.textSize = 15f
        nameIn.isSingleLine = true
        nameIn.background = bg(Color.argb(60, 255, 255, 255), 12)
        nameIn.setPadding(dp(14), dp(10), dp(14), dp(10))
        nameIn.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("name", s.toString()).apply()
                greetName.text = s.toString()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        body.addView(nameIn, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("Accent colour"))
        body.addView(segmented("accent", 0, listOf("Gold", "Ice", "Rose", "Mint"), 0) { applySettings() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(toggle("Show weather", "wx", true) { applySettings() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(settingLabel("Weather city"))
        val cityIn = EditText(this)
        cityIn.setText(prefs.getString("city", DEFAULT_CITY))
        cityIn.setTextColor(Color.WHITE)
        cityIn.textSize = 15f
        cityIn.isSingleLine = true
        cityIn.background = bg(Color.argb(60, 255, 255, 255), 12)
        cityIn.setPadding(dp(14), dp(10), dp(14), dp(10))
        cityIn.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("city", s.toString().trim()).apply()
                cityDirty = true
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        body.addView(cityIn, LinearLayout.LayoutParams(-1, -2))

        if (Build.VERSION.SDK_INT >= 31) {
            body.addView(settingLabel("Background blur (Android 12 and up)"))
            body.addView(seek("blur", 40, 100) { applyBackground() }, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(settingLabel("Background dim"))
        body.addView(seek("dim", 35, 80) { applyBackground(); invalidateGlass() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("App columns"))
        body.addView(segmented("cols", 4, listOf("3", "4", "5"), 3) { applySettings() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("Icon size"))
        body.addView(segmented("icon", 1, listOf("Small", "Medium", "Large"), 0) { applySettings() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(toggle("iOS-style icons", "ios", true) { loadApps() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(toggle("Show today's events", "events", true) { applySettings() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(toggle("Show app names", "labels", true) { applySettings() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(toggle("Search contacts", "contacts", false) {
            if (pBool("contacts", false) &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            }
        }, LinearLayout.LayoutParams(-1, -2))

        body.addView(linkRow("Hidden apps") { showHidden() })
        body.addView(linkRow("Copy settings backup to clipboard") { backupSettings() })
        body.addView(linkRow("Restore settings from clipboard") { restoreSettings() })

        val reset = tv("Reset settings", 15f, Color.rgb(255, 138, 128))
        reset.setPadding(0, dp(18), 0, dp(6))
        reset.setOnClickListener {
            prefs.edit().remove("name").remove("blur").remove("dim").remove("cols")
                .remove("icon").remove("events").remove("labels").remove("accent")
                .remove("wx").remove("city").remove("ios").remove("contacts").apply()
            cityDirty = true
            applySettings()
            loadApps()
            hideSheet()
        }
        body.addView(reset, LinearLayout.LayoutParams(-1, -2))

        openSheet()
    }

    private fun linkRow(text: String, go: () -> Unit): TextView {
        val t = tv(text, 15f, Color.WHITE)
        t.setPadding(0, dp(16), 0, dp(4))
        t.setOnClickListener { go() }
        return t
    }

    private fun settingLabel(text: String): TextView {
        val t = tv(text, 13f, Color.argb(190, 255, 255, 255))
        t.setPadding(0, dp(16), 0, dp(6))
        return t
    }

    private fun seek(key: String, def: Int, max: Int, onChange: () -> Unit): SeekBar {
        val sb = SeekBar(this)
        sb.max = max
        sb.progress = pInt(key, def)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.edit().putInt(key, p).apply()
                    onChange()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        return sb
    }

    private fun segmented(key: String, def: Int, labels: List<String>, base: Int, onChange: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val cells = ArrayList<TextView>()
        fun paint() {
            val curVal = pInt(key, def)
            for (i in cells.indices) {
                val on = (i + base) == curVal
                cells[i].background = bg(if (on) accent() else Color.argb(50, 255, 255, 255), 12)
                cells[i].setTextColor(if (on) Color.rgb(20, 20, 28) else Color.WHITE)
            }
        }
        for (i in labels.indices) {
            val c = tv(labels[i], 14f, Color.WHITE)
            c.gravity = Gravity.CENTER
            c.setOnClickListener {
                prefs.edit().putInt(key, i + base).apply()
                paint()
                onChange()
                paint()
            }
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            lp.rightMargin = if (i < labels.size - 1) dp(8) else 0
            row.addView(c, lp)
            cells.add(c)
        }
        paint()
        return row
    }

    private fun toggle(title: String, key: String, def: Boolean, onChange: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(14), 0, dp(2))
        row.addView(tv(title, 15f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = pBool(key, def)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(key, on).apply()
            onChange()
        }
        row.addView(sw)
        return row
    }

    // ---------------------------------------------------------------- backup / restore

    private fun backupSettings() {
        try {
            val o = JSONObject()
            o.put("jl_backup", 1)
            for ((k, v) in prefs.all) {
                if (k.startsWith("wx_") || k.startsWith("n_") || k == "askedStorage") continue
                if (v is Int || v is Long || v is Boolean || v is String) o.put(k, v)
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JLauncher backup", o.toString()))
            Toast.makeText(this, "Backup copied. Paste it somewhere safe.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreSettings() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val o = JSONObject(txt)
            if (!o.has("jl_backup")) throw IllegalArgumentException()
            val ed = prefs.edit()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k == "jl_backup") continue
                when (val v = o.get(k)) {
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putInt(k, v.toInt())
                    is Boolean -> ed.putBoolean(k, v)
                    is String -> ed.putString(k, v)
                }
            }
            ed.apply()
            initDock()
            cityDirty = true
            applySettings()
            loadApps()
            hideSheet()
            Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No valid backup found in clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- greeting, weather

    private fun updateGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greetHello.text = (if (h < 12) "Good morning," else if (h < 17) "Good afternoon," else "Good evening,")
        dateTv.text = SimpleDateFormat("EEEE · d MMMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
    }

    private fun weatherEmoji(code: Int): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = h < 6 || h >= 19
        return when (code) {
            0 -> if (night) "🌙" else "☀️"
            1, 2 -> if (night) "☁️" else "⛅"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57 -> "🌦️"
            61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
            71, 73, 75, 77, 85, 86 -> "❄️"
            95, 96, 99 -> "⛈️"
            else -> "⛅"
        }
    }

    private fun updateWeather() {
        val t = prefs.getFloat("wx_temp", Float.NaN)
        if (t.isNaN()) {
            wxIcon.text = "⛅"
            wxTemp.text = "--°"
        } else {
            wxIcon.text = weatherEmoji(pInt("wx_code", 2))
            wxTemp.text = Math.round(t).toString() + "°"
        }
    }

    private fun fetchWeather(force: Boolean) {
        if (!pBool("wx", true)) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("wx_t", 0L) < 30 * 60 * 1000L) return
        prefs.edit().putLong("wx_t", now).apply()
        val city = (prefs.getString("city", DEFAULT_CITY) ?: DEFAULT_CITY).ifBlank { DEFAULT_CITY }
        val haveCity = prefs.getString("wx_city", "")
        val lat0 = prefs.getFloat("wx_lat", Float.NaN)
        val lon0 = prefs.getFloat("wx_lon", Float.NaN)
        Thread {
            try {
                var lat = lat0
                var lon = lon0
                if (haveCity != city || lat.isNaN() || lon.isNaN()) {
                    val g = httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8"))
                    val r = JSONObject(g).getJSONArray("results").getJSONObject(0)
                    lat = r.getDouble("latitude").toFloat()
                    lon = r.getDouble("longitude").toFloat()
                    prefs.edit().putFloat("wx_lat", lat).putFloat("wx_lon", lon).putString("wx_city", city).apply()
                }
                val w = httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
                val cur = JSONObject(w).getJSONObject("current")
                prefs.edit()
                    .putFloat("wx_temp", cur.getDouble("temperature_2m").toFloat())
                    .putInt("wx_code", cur.getInt("weather_code"))
                    .apply()
                runOnUiThread { if (!isDestroyed) updateWeather() }
            } catch (e: Exception) {
                prefs.edit().putLong("wx_t", 0L).apply()
            }
        }.start()
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun openWeather() {
        for (p in WEATHER_PKGS) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) {
                startActivity(i)
                return
            }
        }
        val city = prefs.getString("city", DEFAULT_CITY) ?: DEFAULT_CITY
        webSearch("weather $city")
    }

    private fun webSearch(q: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))))
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- today's events

    private fun hasCal() =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun refreshEvents() {
        eventBox.removeAllViews()
        eventBox.addView(tv("TODAY", 10f, accent(), true).also { it.letterSpacing = 0.22f })
        if (!hasCal()) {
            val t = tv("Tap to show your calendar events", 14f, Color.WHITE)
            t.setPadding(0, dp(6), 0, 0)
            eventBox.addView(t)
            eventBox.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CAL) }
            return
        }
        eventBox.setOnClickListener { openCalendar() }
        val list = try { queryEvents() } catch (e: Exception) { emptyList<Ev>() }
        if (list.isEmpty()) {
            val t = tv("Nothing scheduled today", 14f, Color.WHITE)
            t.setPadding(0, dp(6), 0, 0)
            eventBox.addView(t)
            return
        }
        for ((idx, ev) in list.withIndex()) {
            if (idx >= 2) break
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            val bar = View(this)
            bar.background = bg(accent(), 2)
            bar.alpha = if (idx == 0) 1f else 0.55f
            row.addView(bar, LinearLayout.LayoutParams(dp(3), dp(30)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), 0, 0, 0)
            val title = tv(ev.title, 14f, Color.WHITE, true)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            col.addView(title)
            col.addView(tv(ev.whenText, 11f, Color.argb(180, 255, 255, 255)))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = dp(8)
            eventBox.addView(row, lp)
        }
        if (list.size > 2) {
            val more = tv("+" + (list.size - 2) + " more", 11f, Color.argb(160, 255, 255, 255))
            more.setPadding(0, dp(6), 0, 0)
            eventBox.addView(more)
        }
    }

    private fun dateKey(c: Calendar) =
        c.get(Calendar.YEAR) * 10000 + c.get(Calendar.MONTH) * 100 + c.get(Calendar.DAY_OF_MONTH)

    // only events that fall on today (local day), still running or yet to start
    private fun queryEvents(): List<Ev> {
        val now = System.currentTimeMillis()
        val endDay = Calendar.getInstance()
        endDay.set(Calendar.HOUR_OF_DAY, 23)
        endDay.set(Calendar.MINUTE, 59)
        endDay.set(Calendar.SECOND, 59)
        endDay.set(Calendar.MILLISECOND, 999)
        val todayKey = dateKey(Calendar.getInstance())

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, endDay.timeInMillis)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val out = ArrayList<Ev>()
        val c = contentResolver.query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")
        if (c != null) {
            try {
                while (c.moveToNext() && out.size < 6) {
                    val t = c.getString(0)
                    val title = if (t == null || t.isBlank()) "(No title)" else t
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    if (allDay) {
                        // all-day events are stored in UTC dates
                        val cb = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        cb.timeInMillis = begin
                        val ce = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        ce.timeInMillis = end - 1
                        if (todayKey < dateKey(cb) || todayKey > dateKey(ce)) continue
                    } else if (end < now) {
                        continue
                    }
                    out.add(Ev(title, whenText(begin, end, allDay, now)))
                }
            } finally {
                c.close()
            }
        }
        return out
    }

    private fun whenText(begin: Long, end: Long, allDay: Boolean, now: Long): String {
        if (allDay) return "All day"
        val f = SimpleDateFormat("h:mm a", Locale.getDefault())
        if (begin <= now) return "Now · until " + f.format(Date(end))
        return f.format(Date(begin)) + " – " + f.format(Date(end))
    }

    private fun openCalendar() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- apps, search, dock

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        prefs.edit().putInt("n_$pkg", pInt("n_$pkg", 0) + 1).apply()
        val o = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(i, o.toBundle())
    }

    private fun hiddenSet(): MutableSet<String> {
        val s = prefs.getString("hidden", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun saveHidden(s: Set<String>) {
        prefs.edit().putString("hidden", s.joinToString(",")).apply()
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val infos = pm.queryIntentActivities(i, 0).filter { it.activityInfo.packageName != packageName }
        riMap.clear()
        val seen = HashSet<String>()
        val list = ArrayList<App>()
        for (ri in infos) {
            val p = ri.activityInfo.packageName
            if (!seen.add(p)) continue
            riMap[p] = ri
            list.add(App(ri.loadLabel(pm).toString(), p))
        }
        everything = list.sortedBy { it.label.lowercase() }
        val hidden = hiddenSet()
        all = everything.filter { !hidden.contains(it.pkg) }
        filter()
        refreshDock()
        styleMissing()
    }

    private fun letterOf(label: String): Char {
        val ch = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (ch in 'A'..'Z') ch else '#'
    }

    private fun matches(label: String, q: String): Boolean {
        if (label.contains(q)) return true
        val ini = label.split(" ", "-", "_").filter { it.isNotEmpty() }.map { it[0] }.joinToString("")
        return q.length >= 2 && ini.startsWith(q)
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        if (q.isEmpty()) {
            shown.addAll(all)
        } else {
            val m = all.filter { matches(it.label.lowercase(), q) }
            shown.addAll(m.sortedWith(
                compareBy<App>({
                    val l = it.label.lowercase()
                    if (l.startsWith(q)) 0 else if (l.split(" ").any { w -> w.startsWith(q) }) 1 else 2
                }, { -pInt("n_" + it.pkg, 0) }, { it.label.lowercase() })
            ))
        }
        adapter.notifyDataSetChanged()
        letterIndex.clear()
        for ((idx, a) in shown.withIndex()) {
            val l = letterOf(a.label)
            if (!letterIndex.containsKey(l)) letterIndex[l] = idx
        }
        strip.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        updateExtras(q)
    }

    private fun extraRow(glyph: Int, text: String, sub: String?, go: () -> Unit): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(9), 0, dp(9))
        row.addView(Glyph(glyph, accent()), LinearLayout.LayoutParams(dp(20), dp(20)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, 0, 0)
        val t = tv(text, 14f, Color.WHITE, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        if (sub != null) col.addView(tv(sub, 11f, Color.argb(170, 255, 255, 255)))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { go() }
        return row
    }

    private fun updateExtras(q: String) {
        extraBox.removeAllViews()
        if (q.isEmpty()) {
            extraBox.visibility = View.GONE
            return
        }
        val calc = evalExpr(q)
        if (calc != null) {
            extraBox.addView(extraRow(G_CALC, calc, "= " + q) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("result", calc))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            })
        }
        if (pBool("contacts", false) && q.length >= 2 &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val c = contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME),
                    ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?", arrayOf("%$q%"),
                    ContactsContract.Contacts.DISPLAY_NAME + " ASC LIMIT 3")
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val key = c.getString(1)
                            val name = c.getString(2) ?: continue
                            extraBox.addView(extraRow(G_PERSON, name, "Contact") {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(id, key)))
                                } catch (e: Exception) {
                                }
                            })
                        }
                    } finally {
                        c.close()
                    }
                }
            } catch (e: Exception) {
            }
        }
        extraBox.addView(extraRow(G_WEB, "Search the web for “" + q + "”", null) { webSearch(q) })
        extraBox.visibility = View.VISIBLE
    }

    // tiny calculator: + - * / and brackets
    private class Calc(private val s: String) {
        private var i = 0
        fun run(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException()
            return v
        }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        private fun factor(): Double {
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '+') { i++; return factor() }
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException()
                i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return s.substring(st, i).toDouble()
        }
    }

    private fun evalExpr(raw: String): String? {
        val t = raw.replace(" ", "").replace("x", "*").replace("×", "*").replace("÷", "/")
        if (t.none { it.isDigit() } || t.none { it == '+' || it == '-' || it == '*' || it == '/' }) return null
        if (!t.all { it.isDigit() || it == '+' || it == '-' || it == '*' || it == '/' || it == '(' || it == ')' || it == '.' }) return null
        return try {
            val v = Calc(t).run()
            if (v.isNaN() || v.isInfinite()) null
            else if (v == Math.rint(v) && Math.abs(v) < 1e12) v.toLong().toString()
            else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    private fun initDock() {
        val saved = prefs.getString("dock", null)
        dock = mutableListOf()
        if (saved == null) {
            val defaults = listOf(
                Intent(Intent.ACTION_DIAL),
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com")),
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            )
            for (probe in defaults) {
                val pkg = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                if (pkg != null && pkg != "android" && packageManager.getLaunchIntentForPackage(pkg) != null && !dock.contains(pkg)) {
                    dock.add(pkg)
                }
            }
            saveDock()
        } else {
            dock = saved.split(",").filter { it.isNotEmpty() }.take(MAX_DOCK).toMutableList()
        }
    }

    private fun saveDock() {
        prefs.edit().putString("dock", dock.joinToString(",")).apply()
    }

    private fun addToDock(pkg: String) {
        if (dock.size >= MAX_DOCK) {
            Toast.makeText(this, "Dock is full. Remove one first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!dock.contains(pkg)) {
            dock.add(pkg)
            saveDock()
            refreshDock()
        }
    }

    private fun removeFromDock(pkg: String) {
        dock.remove(pkg)
        saveDock()
        refreshDock()
    }

    private fun refreshDock() {
        val apps = dock.mapNotNull { p -> everything.find { it.pkg == p } }
        if (everything.isNotEmpty() && apps.size != dock.size) {
            dock = apps.map { it.pkg }.toMutableList()
            saveDock()
        }
        dockBox.removeAllViews()
        for (i in 0 until MAX_DOCK) {
            val cell = FrameLayout(this)
            if (i < apps.size) {
                val a = apps[i]
                val iv = ImageView(this)
                iv.setImageDrawable(iconOf(a))
                iv.setOnClickListener { launch(a.pkg) }
                iv.setOnLongClickListener { haptic(iv); showSheet(a, true); true }
                pressable(iv)
                cell.addView(iv, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            } else {
                val slot = View(this)
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(Color.argb(45, 255, 255, 255))
                slot.background = g
                cell.addView(slot, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            }
            dockBox.addView(cell, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    // ---------------------------------------------------------------- bottom sheet

    private fun sheetHeader(title: String, appIcon: Drawable?): LinearLayout {
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL
        header.setPadding(0, dp(10), 0, dp(6))
        val handle = View(this)
        handle.background = bg(Color.argb(110, 255, 255, 255), 3)
        header.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)))
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(24), dp(14), dp(24), dp(8))
        if (appIcon != null) {
            val icon = ImageView(this)
            icon.setImageDrawable(appIcon)
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val name = tv(title, 16f, Color.WHITE, true)
        if (appIcon != null) name.setPadding(dp(14), 0, 0, 0)
        row.addView(name)
        header.addView(row, LinearLayout.LayoutParams(-1, -2))
        attachDrag(header)
        return header
    }

    private fun showSheet(app: App, inDock: Boolean) {
        sheet.removeAllViews()
        sheet.addView(sheetHeader(app.label, iconOf(app)), LinearLayout.LayoutParams(-1, -2))

        val acts = ArrayList<Act>()
        if (inDock || dock.contains(app.pkg)) {
            acts.add(Act("Remove from dock", G_PIN, false) { removeFromDock(app.pkg) })
        } else {
            acts.add(Act("Add to dock", G_PIN, false) { addToDock(app.pkg) })
        }
        acts.add(Act("Hide app", G_EYE, false) {
            val s = hiddenSet()
            s.add(app.pkg)
            saveHidden(s)
            removeFromDock(app.pkg)
            loadApps()
            Toast.makeText(this, app.label + " hidden. Settings > Hidden apps to bring it back.", Toast.LENGTH_LONG).show()
        })
        acts.add(Act("App info", G_INFO, false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))
        })
        acts.add(Act("Uninstall", G_TRASH, true) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))
        })
        for ((idx, act) in acts.withIndex()) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(24), 0, dp(24), 0)
            val col = if (act.danger) Color.rgb(255, 154, 143) else accent()
            row.addView(Glyph(act.glyph, col), LinearLayout.LayoutParams(dp(20), dp(20)))
            val t = tv(act.label, 15f, if (act.danger) col else Color.WHITE)
            t.setPadding(dp(14), 0, 0, 0)
            row.addView(t)
            row.setOnClickListener { hideSheet(); act.go() }
            sheet.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
            if (idx < acts.size - 1) {
                val sep = View(this)
                sep.setBackgroundColor(Color.argb(24, 255, 255, 255))
                sheet.addView(sep, LinearLayout.LayoutParams(-1, 1))
            }
        }
        sheet.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
        openSheet()
    }

    private fun showHidden() {
        sheet.removeAllViews()
        sheet.addView(sheetHeader("Hidden apps", null), LinearLayout.LayoutParams(-1, -2))
        val sv = MaxScroll((resources.displayMetrics.heightPixels * 0.5f).toInt())
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(24), dp(2), dp(24), dp(18))
        sv.addView(body, ViewGroup.LayoutParams(-1, -2))
        sheet.addView(sv, LinearLayout.LayoutParams(-1, -2))
        val hidden = hiddenSet()
        val apps = everything.filter { hidden.contains(it.pkg) }
        if (apps.isEmpty()) {
            body.addView(tv("No hidden apps", 14f, Color.argb(190, 255, 255, 255)))
        }
        for (a in apps) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            val ic = ImageView(this)
            ic.setImageDrawable(iconOf(a))
            row.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)))
            val t = tv(a.label, 15f, Color.WHITE)
            t.setPadding(dp(14), 0, 0, 0)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("Unhide", 13f, accent(), true))
            row.setOnClickListener {
                val s = hiddenSet()
                s.remove(a.pkg)
                saveHidden(s)
                loadApps()
                showHidden()
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // sheet content height changed while open: re-fit
    }

    private fun openSheet() {
        sheetOpen = true
        sheet.animate().cancel()
        scrim.animate().cancel()
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        sheet.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        sheet.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheet.viewTreeObserver.removeOnPreDrawListener(this)
                sheet.translationY = sheet.height.toFloat()
                sheet.animate().translationY(0f).setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .setUpdateListener { sheetGlass?.invalidateSelf() }
                    .withEndAction { sheetGlass?.invalidateSelf() }
                    .start()
                return true
            }
        })
    }

    private fun hideSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        hideKeyboard()
        if (cityDirty) {
            cityDirty = false
            prefs.edit().putString("wx_city", "").apply()
            fetchWeather(true)
        }
        scrim.animate().alpha(0f).setDuration(180).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180)
            .setUpdateListener { sheetGlass?.invalidateSelf() }
            .withEndAction {
                sheet.visibility = View.GONE
                scrim.visibility = View.GONE
            }.start()
    }

    // swipe down on the sheet header to dismiss
    private fun attachDrag(v: View) {
        var startY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    sheet.translationY = maxOf(0f, e.rawY - startY)
                    sheetGlass?.invalidateSelf()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > dp(80)) hideSheet()
                    else sheet.animate().translationY(0f).setDuration(150)
                        .setUpdateListener { sheetGlass?.invalidateSelf() }.start()
                    true
                }
                else -> false
            }
        }
    }
}

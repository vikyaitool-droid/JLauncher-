package com.vikranth.jlauncher

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {

    companion object {
        const val NAME = "Dr. Vikranth"   // default greeting name (change in launcher settings)
        const val MAX_DOCK = 6
        const val REQ_CAL = 7
    }

    data class App(val label: String, val pkg: String, val icon: Drawable)
    data class Ev(val title: String, val whenText: String)
    data class Act(val label: String, val go: () -> Unit)

    private var all = listOf<App>()
    private val shown = mutableListOf<App>()
    private var dock = mutableListOf<String>()

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: BaseAdapter
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var greetHello: TextView
    private lateinit var greetName: TextView
    private lateinit var greetDate: TextView
    private lateinit var eventBox: LinearLayout
    private lateinit var dockBox: LinearLayout
    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private var sheetOpen = false

    private val tick = object : Runnable {
        override fun run() {
            updateGreeting()
            refreshEvents()
            eventBox.postDelayed(this, 60000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pInt(k: String, d: Int) = prefs.getInt(k, d)
    private fun pBool(k: String, d: Boolean) = prefs.getBoolean(k, d)

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
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("jl", MODE_PRIVATE)
        window.statusBarColor = Color.TRANSPARENT
        initDock()
        buildUi()
        applySettings()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { handleBack() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        search.setText("")
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
        refreshEvents()
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

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)
        root.fitsSystemWindows = true

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(dp(12), dp(8), dp(12), dp(8))
        main.isFocusable = true
        main.isFocusableInTouchMode = true

        // greeting card (long-press = settings)
        greetHello = tv("", 13f, Color.argb(200, 255, 255, 255))
        greetDate = tv("", 12f, Color.argb(170, 255, 255, 255))
        greetName = tv(NAME, 19f, Color.WHITE, true)
        val greetCol = LinearLayout(this)
        greetCol.orientation = LinearLayout.VERTICAL
        greetCol.addView(greetHello)
        greetCol.addView(greetName)
        greetCol.addView(greetDate)
        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 30f
        clock.setTextColor(Color.WHITE)
        val greet = LinearLayout(this)
        greet.gravity = Gravity.CENTER_VERTICAL
        greet.background = bg(Color.argb(150, 16, 22, 30), 22)
        greet.setPadding(dp(16), dp(14), dp(14), dp(14))
        greet.addView(greetCol, LinearLayout.LayoutParams(0, -2, 1f))
        greet.addView(clock)
        greet.setOnLongClickListener { showSettings(); true }
        main.addView(greet, LinearLayout.LayoutParams(-1, -2))

        // today's events card
        eventBox = LinearLayout(this)
        eventBox.orientation = LinearLayout.VERTICAL
        eventBox.background = bg(Color.argb(150, 16, 22, 30), 22)
        eventBox.setPadding(dp(16), dp(12), dp(16), dp(12))
        val evLp = LinearLayout.LayoutParams(-1, -2)
        evLp.topMargin = dp(10)
        main.addView(eventBox, evLp)

        // full-width search row + settings button
        search = EditText(this)
        search.hint = "Search apps"
        search.setHintTextColor(Color.argb(160, 255, 255, 255))
        search.setTextColor(Color.WHITE)
        search.textSize = 14f
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.background = bg(Color.argb(70, 255, 255, 255), 22)
        search.setPadding(dp(16), 0, dp(16), 0)
        search.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val gear = ImageView(this)
        gear.setImageResource(android.R.drawable.ic_menu_preferences)
        gear.setColorFilter(Color.WHITE)
        gear.setPadding(dp(10), dp(10), dp(10), dp(10))
        gear.background = bg(Color.argb(70, 255, 255, 255), 22)
        gear.setOnClickListener { showSettings() }
        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
        val gearLp = LinearLayout.LayoutParams(dp(44), dp(44))
        gearLp.leftMargin = dp(8)
        searchRow.addView(gear, gearLp)
        val srLp = LinearLayout.LayoutParams(-1, -2)
        srLp.topMargin = dp(10)
        main.addView(searchRow, srLp)

        // app grid
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val a = shown[i]
                val sizes = intArrayOf(40, 46, 54)
                val sz = sizes[pInt("icon", 1).coerceIn(0, 2)]
                val cell = LinearLayout(this@MainActivity)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(0, dp(8), 0, dp(8))
                val icon = ImageView(this@MainActivity)
                icon.setImageDrawable(a.icon)
                cell.addView(icon, LinearLayout.LayoutParams(dp(sz), dp(sz)))
                if (pBool("labels", true)) {
                    val label = tv(a.label, 11f, Color.WHITE)
                    label.maxLines = 1
                    label.ellipsize = TextUtils.TruncateAt.END
                    label.gravity = Gravity.CENTER
                    label.setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    label.setPadding(dp(2), dp(4), dp(2), 0)
                    cell.addView(label, LinearLayout.LayoutParams(-1, -2))
                }
                return cell
            }
        }
        grid = GridView(this)
        grid.numColumns = 4
        grid.adapter = adapter
        grid.selector = bg(Color.argb(50, 255, 255, 255), 14)
        grid.isVerticalScrollBarEnabled = false
        grid.setOnItemClickListener { _, _, i, _ -> launch(shown[i].pkg) }
        grid.setOnItemLongClickListener { _, _, i, _ -> showSheet(shown[i], false); true }

        // right fixed dock, sits low
        dockBox = LinearLayout(this)
        dockBox.orientation = LinearLayout.VERTICAL
        dockBox.gravity = Gravity.CENTER_HORIZONTAL
        dockBox.background = bg(Color.argb(120, 16, 22, 30), 30)
        dockBox.setPadding(dp(6), dp(8), dp(6), dp(8))
        val dockWrap = LinearLayout(this)
        dockWrap.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dockWrap.setPadding(0, 0, 0, dp(16))
        dockWrap.addView(dockBox, LinearLayout.LayoutParams(-2, -2))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        body.addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
        val dwLp = LinearLayout.LayoutParams(dp(60), -1)
        dwLp.leftMargin = dp(6)
        body.addView(dockWrap, dwLp)
        val bodyLp = LinearLayout.LayoutParams(-1, 0, 1f)
        bodyLp.topMargin = dp(6)
        main.addView(body, bodyLp)

        // bottom sheet + scrim
        scrim = View(this)
        scrim.setBackgroundColor(Color.argb(130, 0, 0, 0))
        scrim.visibility = View.GONE
        scrim.setOnClickListener { hideSheet() }

        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true
        sheet.visibility = View.GONE
        val r = dp(24).toFloat()
        val sbg = GradientDrawable()
        sbg.setColor(Color.argb(250, 28, 32, 40))
        sbg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = sbg

        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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
        applyBackground()
    }

    // dim always works; blur needs Android 12+ and blur effects enabled in the system
    private fun applyBackground() {
        val dim = pInt("dim", 35)
        window.setBackgroundDrawable(ColorDrawable(Color.argb(dim * 255 / 100, 0, 0, 0)))
        if (Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(dp(pInt("blur", 40) * 6 / 10))
        }
    }

    private fun showSettings() {
        sheet.removeAllViews()
        val header = sheetHeader("Launcher settings", null)
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2))

        val sv = ScrollView(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(24), dp(4), dp(24), dp(24))
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

        body.addView(settingLabel("Background blur (Android 12 and up)"))
        body.addView(seek("blur", 40, 100) { applyBackground() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("Background dim"))
        body.addView(seek("dim", 35, 80) { applyBackground() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("App columns"))
        body.addView(segmented("cols", 4, listOf("3", "4", "5"), 3) { applySettings() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(settingLabel("Icon size"))
        body.addView(segmented("icon", 1, listOf("Small", "Medium", "Large"), 0) { applySettings() }, LinearLayout.LayoutParams(-1, -2))

        body.addView(toggle("Show today's events", "events", true) { applySettings() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(toggle("Show app names", "labels", true) { applySettings() }, LinearLayout.LayoutParams(-1, -2))

        val reset = tv("Reset settings", 15f, Color.rgb(255, 138, 128))
        reset.setPadding(0, dp(18), 0, dp(6))
        reset.setOnClickListener {
            prefs.edit().remove("name").remove("blur").remove("dim").remove("cols")
                .remove("icon").remove("events").remove("labels").apply()
            applySettings()
            hideSheet()
        }
        body.addView(reset, LinearLayout.LayoutParams(-1, -2))

        openSheet()
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
                cells[i].background = bg(if (on) Color.rgb(66, 133, 244) else Color.argb(50, 255, 255, 255), 12)
            }
        }
        for (i in labels.indices) {
            val c = tv(labels[i], 14f, Color.WHITE)
            c.gravity = Gravity.CENTER
            c.setOnClickListener {
                prefs.edit().putInt(key, i + base).apply()
                paint()
                onChange()
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

    // ---------------------------------------------------------------- greeting + today's events

    private fun updateGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greetHello.text = if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
        greetDate.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
    }

    private fun hasCal() =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun refreshEvents() {
        eventBox.removeAllViews()
        eventBox.addView(tv("Today", 12f, Color.rgb(150, 200, 255), true))
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
        for (ev in list) {
            val title = tv(ev.title, 14f, Color.WHITE)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = dp(8)
            eventBox.addView(title, lp)
            eventBox.addView(tv(ev.whenText, 11f, Color.argb(180, 255, 255, 255)))
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
                while (c.moveToNext() && out.size < 4) {
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

    // ---------------------------------------------------------------- apps + dock

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
    }

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        all = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.loadIcon(packageManager)) }
            .sortedBy { it.label.lowercase() }
        filter()
        refreshDock()
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        shown.addAll(if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) })
        adapter.notifyDataSetChanged()
    }

    private fun initDock() {
        val saved = prefs.getString("dock", null)
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
            dock = saved.split(",").filter { it.isNotEmpty() }.toMutableList()
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
        val apps = dock.mapNotNull { p -> all.find { it.pkg == p } }
        if (all.isNotEmpty() && apps.size != dock.size) {
            dock = apps.map { it.pkg }.toMutableList()
            saveDock()
        }
        dockBox.removeAllViews()
        for (i in 0 until MAX_DOCK) {
            if (i < apps.size) {
                val a = apps[i]
                val iv = ImageView(this)
                iv.setImageDrawable(a.icon)
                iv.setOnClickListener { launch(a.pkg) }
                iv.setOnLongClickListener { showSheet(a, true); true }
                val lp = LinearLayout.LayoutParams(dp(42), dp(42))
                lp.setMargins(0, dp(5), 0, dp(5))
                dockBox.addView(iv, lp)
            } else {
                val slot = View(this)
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(Color.argb(45, 255, 255, 255))
                slot.background = g
                val lp = LinearLayout.LayoutParams(dp(30), dp(30))
                lp.setMargins(0, dp(11), 0, dp(11))
                dockBox.addView(slot, lp)
            }
        }
    }

    // ---------------------------------------------------------------- bottom sheet

    private fun sheetHeader(title: String, appIcon: Drawable?): LinearLayout {
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL
        header.setPadding(0, dp(10), 0, dp(6))
        val handle = View(this)
        handle.background = bg(Color.argb(90, 255, 255, 255), 3)
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
        sheet.addView(sheetHeader(app.label, app.icon), LinearLayout.LayoutParams(-1, -2))

        val acts = ArrayList<Act>()
        if (inDock || dock.contains(app.pkg)) {
            acts.add(Act("Remove from dock") { removeFromDock(app.pkg) })
        } else {
            acts.add(Act("Add to dock") { addToDock(app.pkg) })
        }
        acts.add(Act("App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))
        })
        acts.add(Act("Uninstall") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))
        })
        for (act in acts) {
            val t = tv(act.label, 15f, Color.WHITE)
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(dp(24), 0, dp(24), 0)
            t.setOnClickListener { hideSheet(); act.go() }
            sheet.addView(t, LinearLayout.LayoutParams(-1, dp(52)))
        }
        sheet.addView(View(this), LinearLayout.LayoutParams(-1, dp(14)))
        openSheet()
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
                sheet.animate().translationY(0f).setDuration(220).start()
                return true
            }
        })
    }

    private fun hideSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(180).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).withEndAction {
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
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > dp(80)) hideSheet()
                    else sheet.animate().translationY(0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }
}

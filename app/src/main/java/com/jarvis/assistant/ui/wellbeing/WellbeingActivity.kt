package com.jarvis.assistant.ui.wellbeing

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.wellbeing.ScreenTimeReader
import com.jarvis.assistant.wellbeing.WellbeingController
import com.jarvis.assistant.wellbeing.WellbeingPrefs

/**
 * Phone Wellbeing settings. One tap on a preset chip sets a value; "Custom" opens a picker.
 * Built in code (no layout file) so it has no ViewBinding dependency.
 */
class WellbeingActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    private var cPrimary = Color.WHITE
    private var cMuted = Color.GRAY
    private var cAccent = Color.CYAN
    private var cCard = Color.DKGRAY
    private var cBorder = Color.GRAY
    private val cWarn = Color.parseColor("#FFB020")

    // limit presets in minutes (0 = off)
    private val limitPresets = listOf(0 to "Off", 60 to "1h", 120 to "2h", 180 to "3h", 240 to "4h", 300 to "5h")

    // bedtime presets as minute of day
    private val bedPresets = listOf(22 * 60 to "10 PM", 23 * 60 to "11 PM", 0 to "12 AM", 60 to "1 AM", 120 to "2 AM")

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        cPrimary = attrColor(R.attr.themeTextPrimary, Color.WHITE)
        cMuted = attrColor(R.attr.themeTextMuted, Color.GRAY)
        cAccent = attrColor(R.attr.themeAccent, Color.CYAN)
        cCard = attrColor(R.attr.themeCardBg, Color.DKGRAY)
        cBorder = attrColor(R.attr.themeCardBorder, Color.GRAY)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "\u2190"
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Phone Wellbeing"
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        content.removeAllViews()
        val hasPerm = ScreenTimeReader.hasPermission(this)
        val master = WellbeingPrefs.masterEnabled(this)
        val daily = WellbeingPrefs.dailyLimitMin(this)
        val today = WellbeingPrefs.todayLimitMin(this)

        // ---- usage
        val usage = card()
        if (hasPerm) {
            val min = (ScreenTimeReader.usedTodayMillis(this) / 60_000L).toInt()
            usage.addView(label("Aaj ka screen time", 13f, cMuted))
            usage.addView(label("${min / 60}h ${min % 60}m", 28f, cPrimary, bold = true))
            usage.addView(label("Forced locks aaj: ${WellbeingPrefs.locksToday(this)} / 3", 12f, cMuted))
        } else {
            usage.addView(label("Limit kaam karne ke liye \"Usage access\" allow karna zaroori hai.", 14f, cWarn))
            usage.addView(button("Usage access kholo") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
        }
        content.addView(usage)

        // ---- switches
        val switches = card()
        switches.addView(switchRow(
            "Wellbeing",
            "Limit paar hone par aur raat ko Jarvis bolega",
            master
        ) { WellbeingPrefs.setMasterEnabled(this, it); changed() })
        switches.addView(divider())
        switches.addView(switchRow(
            "Random baat",
            "Jarvis kabhi kabhi khud baat shuru karega",
            WellbeingPrefs.chatEnabled(this)
        ) { WellbeingPrefs.setChatEnabled(this, it); WellbeingController.refresh() })
        content.addView(switches)

        // ---- limits
        val limits = card()
        limits.addView(durationSection("Daily limit (roz)", daily) { WellbeingPrefs.setDailyLimitMin(this, it); changed() })
        limits.addView(divider())
        limits.addView(durationSection("Sirf aaj ka limit", today) { WellbeingPrefs.setTodayLimitMin(this, it); changed() })
        if ((daily > 0 || today > 0) && !master) {
            limits.addView(label("Wellbeing switch off hai, isliye limit abhi kaam nahi karegi.", 12f, cWarn).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        content.addView(limits)

        // ---- bedtime
        val bed = card()
        bed.addView(switchRow(
            "Raat ka reminder",
            "Sone ke time ke baad phone chalane par",
            WellbeingPrefs.bedtimeEnabled(this)
        ) { WellbeingPrefs.setBedtimeEnabled(this, it); WellbeingController.refresh() })
        bed.addView(bedtimeSection())
        content.addView(bed)

        content.addView(label(
            "Bol kar bhi set kar sakte ho: \"Jarvis, aaj sirf 2 ghante phone chalaunga\" ya \"roz 3 ghante se zyada phone nahi chalaunga\".\n\n" +
                "Jarvis sirf screen lock karta hai (phone band nahi). Call, video call aur navigation ke dauran kabhi lock nahi hota.",
            12f, cMuted
        ).apply { setPadding(dp(4), dp(8), dp(4), dp(24)) })
    }

    private fun changed() {
        WellbeingController.refresh()
        refresh()
    }

    // ------------------------------------------------------------ sections

    private fun durationSection(title: String, currentMin: Int, onSet: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(title, 15f, cPrimary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(label(formatDuration(currentMin), 14f, cAccent, bold = true))
        box.addView(head)

        val labels = limitPresets.map { it.second } + "Custom"
        val selected = limitPresets.indexOfFirst { it.first == currentMin }.let { if (it >= 0) it else limitPresets.size }
        box.addView(chipRow(labels, selected) { i ->
            if (i < limitPresets.size) onSet(limitPresets[i].first)
            else showDurationPicker(title, currentMin, onSet)
        })
        return box
    }

    private fun bedtimeSection(): View {
        val current = WellbeingPrefs.bedtimeMinute(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label("Sone ka time", 15f, cPrimary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(label(formatTime(current), 14f, cAccent, bold = true))
        box.addView(head)

        val labels = bedPresets.map { it.second } + "Custom"
        val selected = bedPresets.indexOfFirst { it.first == current }.let { if (it >= 0) it else bedPresets.size }
        box.addView(chipRow(labels, selected) { i ->
            if (i < bedPresets.size) {
                WellbeingPrefs.setBedtimeMinute(this, bedPresets[i].first)
                changed()
            } else {
                TimePickerDialog(this, { _, h, m ->
                    WellbeingPrefs.setBedtimeMinute(this, h * 60 + m)
                    changed()
                }, current / 60, current % 60, false).show()
            }
        })
        return box
    }

    private fun showDurationPicker(title: String, currentMin: Int, onSet: (Int) -> Unit) {
        val hours = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = (currentMin / 60).coerceIn(0, 23)
            wrapSelectorWheel = false
        }
        val minuteLabels = Array(12) { "%02d".format(it * 5) }
        val minutes = NumberPicker(this).apply {
            minValue = 0
            maxValue = 11
            displayedValues = minuteLabels
            value = ((currentMin % 60) / 5).coerceIn(0, 11)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(hours)
            addView(TextView(this@WellbeingActivity).apply {
                text = " ghante  "
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(minutes)
            addView(TextView(this@WellbeingActivity).apply {
                text = " min"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(row)
            .setPositiveButton("Set") { _, _ -> onSet(hours.value * 60 + minutes.value * 5) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ formatting

    private fun formatDuration(min: Int): String = when {
        min <= 0 -> "Off"
        min % 60 == 0 -> "${min / 60}h"
        min < 60 -> "${min}m"
        else -> "${min / 60}h ${min % 60}m"
    }

    private fun formatTime(min: Int): String {
        val h24 = min / 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return "%d:%02d %s".format(h12, min % 60, if (h24 < 12) "AM" else "PM")
    }

    // ------------------------------------------------------------ view helpers

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(cCard)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), cBorder)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(cBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cAccent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
        setOnClickListener { onClick() }
    }

    private fun chipRow(labels: List<String>, selected: Int, onClick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, text ->
            row.addView(TextView(this).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (i == selected) Color.BLACK else cPrimary)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    if (i == selected) {
                        setColor(cAccent)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), cBorder)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { onClick(i) }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, 0)
            addView(row)
        }
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(label(title, 15f, cPrimary, bold = true))
        texts.addView(label(sub, 12f, cMuted))
        val sw = SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(texts)
        row.addView(sw)
        return row
    }

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

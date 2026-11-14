package com.jarvis.assistant.ui.reminder

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.reminder.Reminder
import com.jarvis.assistant.reminder.ReminderManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists all reminders with their time, repeat type and status.
 * Each one can be paused/resumed with a switch or deleted with a button.
 * Built in code (no layout file) so it has no ViewBinding dependency.
 */
class ReminderActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView

    private var cPrimary = Color.WHITE
    private var cMuted = Color.GRAY
    private var cAccent = Color.CYAN
    private var cCard = Color.DKGRAY
    private var cBorder = Color.GRAY

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

        // Header: back + title
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "\u2190"
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "Reminders"
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        countView = TextView(this).apply {
            setTextColor(cMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        titleBox.addView(title)
        titleBox.addView(countView)
        header.addView(back)
        header.addView(titleBox)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        emptyView = TextView(this).apply {
            text = "Abhi koi reminder nahi hai.\nJarvis se bolo: \"kal subah 8 baje yaad dilana...\""
            setTextColor(cMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listContainer.addView(emptyView)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listContainer)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = ReminderManager.list(this)
            .sortedWith(compareByDescending<Reminder> { it.enabled }.thenBy { it.triggerAt })

        listContainer.removeAllViews()
        if (items.isEmpty()) {
            listContainer.addView(emptyView)
            countView.text = "No reminders"
            return
        }
        val active = items.count { it.enabled }
        countView.text = "$active active \u2022 ${items.size - active} paused"
        items.forEach { listContainer.addView(buildRow(it)) }
    }

    private fun buildRow(r: Reminder): View {
        val fmt = SimpleDateFormat("EEE, d MMM yyyy \u2022 h:mm a", Locale.getDefault())
        val repeatText = if (r.repeat == "daily") "Daily" else "Once"
        val whenText = fmt.format(Date(r.triggerAt))
        val subText = if (r.enabled) "Next: $whenText \u2022 $repeatText" else "Paused \u2022 $whenText \u2022 $repeatText"

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(cCard)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), cBorder)
            }
            alpha = if (r.enabled) 1f else 0.6f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = r.message
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = subText
            setTextColor(if (r.enabled) cAccent else cMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        })

        val toggle = SwitchCompat(this).apply {
            isChecked = r.enabled
            setOnCheckedChangeListener { _, checked ->
                if (!ReminderManager.setEnabled(this@ReminderActivity, r.id, checked)) {
                    Toast.makeText(
                        this@ReminderActivity,
                        "Is reminder ka time nikal chuka hai, ise on nahi kar sakte.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                post { refresh() }
            }
        }

        val delete = TextView(this).apply {
            text = "Delete"
            setTextColor(Color.parseColor("#FF5C5C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(8), dp(6), dp(8))
            setOnClickListener { confirmDelete(r) }
        }

        card.addView(texts)
        card.addView(toggle)
        card.addView(delete)
        return card
    }

    private fun confirmDelete(r: Reminder) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete reminder?")
            .setMessage(r.message)
            .setPositiveButton("Delete") { _, _ ->
                ReminderManager.remove(this, r.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

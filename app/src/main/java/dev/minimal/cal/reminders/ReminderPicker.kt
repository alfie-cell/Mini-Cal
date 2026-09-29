package dev.minimal.cal.reminders

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import dev.minimal.cal.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Multi-reminder picker shared by calendar settings and the event view: a checklist of common
 * times, plus "Custom…" for anything else. Values follow [ReminderOverride.minutes]:
 * minutes before (timed events) or offsets from local midnight (all-day events).
 */
object ReminderPicker {

    private const val DEFAULT_CUSTOM_MINUTES = 20
    val TIMED_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 24 * 60, 2 * 24 * 60, 7 * 24 * 60)
    val ALLDAY_PRESETS = listOf(9 * 60, 8 * 60, 12 * 60, -6 * 60, -3 * 60, -15 * 60)

    /**
     * @param onSave chosen values (empty = no reminders)
     * @param onReset shown as a "Default" button when non-null (clears an override)
     */
    fun show(
        activity: Activity,
        title: String,
        allDay: Boolean,
        current: List<Int>,
        onSave: (List<Int>) -> Unit,
        onReset: (() -> Unit)? = null,
    ) {
        val options = ((if (allDay) ALLDAY_PRESETS else TIMED_PRESETS) + current).distinct()
            .sortedWith(if (allDay) compareBy { it } else compareBy<Int> { it })
        val checked = BooleanArray(options.size) { options[it] in current }
        val labels = options.map { label(activity, it, allDay) }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.save) { _, _ -> onSave(options.filterIndexed { i, _ -> checked[i] }) }
            .setNeutralButton(R.string.reminder_custom) { _, _ ->
                val selected = options.filterIndexed { i, _ -> checked[i] }
                custom(activity, allDay) { value ->
                    show(activity, title, allDay, (selected + value).distinct(), onSave, onReset)
                }
            }
        if (onReset != null) dialog.setNegativeButton(R.string.reminder_use_default) { _, _ -> onReset() }
        dialog.show()
    }

    /** Asks for one custom value: "N minutes/hours/days before", or a time on the day / day before. */
    private fun custom(activity: Activity, allDay: Boolean, onValue: (Int) -> Unit) {
        if (allDay) {
            val is24 = DateFormat.is24HourFormat(activity)
            TimePickerDialog(activity, { _, h, m ->
                AlertDialog.Builder(activity)
                    .setItems(arrayOf(activity.getString(R.string.reminder_on_the_day), activity.getString(R.string.reminder_the_day_before))) { _, which ->
                        val minuteOfDay = h * 60 + m
                        onValue(if (which == 0) minuteOfDay else minuteOfDay - 24 * 60)
                    }
                    .show()
            }, 9, 0, is24).show()
            return
        }
        val pad = (activity.resources.displayMetrics.density * 20).toInt()
        val number = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DEFAULT_CUSTOM_MINUTES.toString())
            setSelectAllOnFocus(true)
        }
        val unitIds = IntArray(3) { View.generateViewId() }
        val units = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf(R.string.reminder_unit_minutes, R.string.reminder_unit_hours, R.string.reminder_unit_days).forEachIndexed { i, res ->
                addView(RadioButton(activity).apply { id = unitIds[i]; setText(res) })
            }
            check(unitIds[0])
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(number)
            addView(units)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.reminder_custom_title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = number.text.toString().toIntOrNull()?.coerceIn(0, 60 * 24 * 60) ?: return@setPositiveButton
                val multiplier = when (units.checkedRadioButtonId) { unitIds[1] -> 60; unitIds[2] -> 24 * 60; else -> 1 }
                onValue((n * multiplier).coerceAtMost(60 * 24 * 60))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "At start", "15 min before", "2 hours before", "1 day before", "9:00 on the day", "18:00 the day before". */
    fun label(context: Context, value: Int, allDay: Boolean): String {
        if (allDay) {
            val dayBefore = value < 0
            val minuteOfDay = ((value % (24 * 60)) + 24 * 60) % (24 * 60)
            val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
                .format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault()))
            return context.getString(if (dayBefore) R.string.reminder_allday_day_before else R.string.reminder_allday_on_day, time)
        }
        val r = context.resources
        return when {
            value == 0 -> context.getString(R.string.reminder_at_start)
            value % (24 * 60) == 0 -> r.getQuantityString(R.plurals.reminder_days_before, value / (24 * 60), value / (24 * 60))
            value % 60 == 0 -> r.getQuantityString(R.plurals.reminder_hours_before, value / 60, value / 60)
            else -> context.getString(R.string.reminder_minutes_before, value)
        }
    }

    fun labels(context: Context, values: List<Int>, allDay: Boolean): String =
        if (values.isEmpty()) context.getString(R.string.reminder_none)
        else values.sortedDescending().let { v -> if (allDay) values.sorted() else v }
            .joinToString(", ") { label(context, it, allDay) }
}

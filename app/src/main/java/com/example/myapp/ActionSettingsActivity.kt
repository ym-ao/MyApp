package com.example.myapp

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.storage.ActionStore
import com.example.myapp.storage.ActionType
import com.example.myapp.storage.CustomAction
import com.example.myapp.storage.CustomActions

/**
 * The settings of one step of the custom list, shown as a dialog (like an auto clicker's "Tap Settings"):
 * touch and hold time, delay after the step, repetitions and anti-detection. Opened by tapping a marker
 * in the click window, or from the Clicks tab. Every time is in milliseconds, from and to.
 */
class ActionSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"

        /** Set when the screen really opened, so the floating window can tell if Android blocked it. */
        @Volatile
        var opened = false
    }

    private var index = -1
    private lateinit var data: CustomActions
    private lateinit var action: CustomAction

    private var holdMin: EditText? = null
    private var holdMax: EditText? = null
    private lateinit var waitMin: EditText
    private lateinit var waitMax: EditText
    private lateinit var repeatInput: EditText
    private var antiDetection: CheckBox? = null
    private var offsetInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened = true
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        data = ActionStore.load(this)
        val found = data.actions.getOrNull(index)
        if (found == null) {
            Toast.makeText(this, "That step no longer exists", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        action = found
        setContentView(buildUi())
    }

    private fun typeName(): String = when (action.type) {
        ActionType.CLICK -> "Tap"
        ActionType.DRAG -> "Swipe"
        ActionType.HOLD -> "Hold"
        ActionType.WAIT -> "Wait"
        ActionType.SORT -> "Sort"
        ActionType.MERGE -> "Merge"
        ActionType.SUMMON -> "Summon"
    }

    private fun number(value: Long): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
    }

    private fun label(box: LinearLayout, text: String) {
        box.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        })
    }

    /** Two boxes side by side: from and to. */
    private fun pair(box: LinearLayout, a: EditText, b: EditText) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = "${typeName()} settings #${index + 1}"
            textSize = 20f
        })
        box.addView(TextView(this).apply {
            text = "Times are in milliseconds. Same number in both boxes = fixed; a range = a random time in it every time."
            textSize = 12f
        })

        val hasHold = action.type == ActionType.CLICK || action.type == ActionType.DRAG ||
            action.type == ActionType.HOLD || action.type == ActionType.WAIT
        if (hasHold) {
            label(
                box, when (action.type) {
                    ActionType.CLICK -> "Touch & hold delay (how long the finger stays down), from / to"
                    ActionType.DRAG -> "Swipe duration, from / to"
                    ActionType.HOLD -> "Hold time (as long as you want), from / to"
                    else -> "Wait time, from / to"
                }
            )
            val a = number(action.holdMin)
            val b = number(action.holdMax)
            holdMin = a
            holdMax = b
            pair(box, a, b)
        }

        label(box, "Gesture delay (pause after this step), from / to")
        waitMin = number(action.waitMin)
        waitMax = number(action.waitMax)
        pair(box, waitMin, waitMax)

        label(box, "Number of repetitions (done in a row)")
        repeatInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(action.repeat.toString())
        }
        box.addView(repeatInput)

        if (action.type == ActionType.CLICK || action.type == ActionType.DRAG || action.type == ActionType.HOLD) {
            val check = CheckBox(this).apply {
                text = "Anti-detection: a random offset to the position every time"
                isChecked = action.jitter > 0
            }
            antiDetection = check
            box.addView(check)
            label(box, "Offset size (pixels)")
            val offset = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((if (action.jitter > 0) action.jitter else 6).toString())
            }
            offsetInput = offset
            box.addView(offset)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "Delete"
            setTextColor(0xFFC62828.toInt())
            setOnClickListener { delete() }
        })
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        buttons.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { save() }
        })
        box.addView(buttons)

        return ScrollView(this).apply { addView(box) }
    }

    private fun readLong(e: EditText, fallback: Long, lo: Long, hi: Long): Long =
        e.text.toString().toLongOrNull()?.coerceIn(lo, hi) ?: fallback

    private fun save() {
        val hm = holdMin
        val hx = holdMax
        if (hm != null && hx != null) {
            val lo = readLong(hm, action.holdMin, 0L, 86_400_000L)
            action.holdMin = lo
            action.holdMax = readLong(hx, action.holdMax, 0L, 86_400_000L).coerceAtLeast(lo)
        }
        val wlo = readLong(waitMin, action.waitMin, 0L, 3_600_000L)
        action.waitMin = wlo
        action.waitMax = readLong(waitMax, action.waitMax, 0L, 3_600_000L).coerceAtLeast(wlo)
        action.repeat = (repeatInput.text.toString().toIntOrNull() ?: action.repeat).coerceIn(1, 1_000_000)
        val check = antiDetection
        if (check != null) {
            val size = (offsetInput?.text.toString().toIntOrNull() ?: 6).coerceIn(0, 200)
            action.jitter = if (check.isChecked) (if (size == 0) 6 else size) else 0
        }
        ActionStore.save(this, data)
        AutomationService.instance?.refreshMarkers()
        finish()
    }

    private fun delete() {
        data.actions.removeAt(index)
        ActionStore.save(this, data)
        AutomationService.instance?.refreshMarkers()
        finish()
    }
}

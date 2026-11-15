package com.example.myapp

import android.os.Bundle
import android.view.LayoutInflater
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.myapp.databinding.ActivityActionsBinding
import com.example.myapp.storage.ActionStore
import com.example.myapp.storage.ActionType
import com.example.myapp.storage.CustomAction
import com.example.myapp.storage.CustomActions
import com.example.myapp.storage.SettingsStore

/** The Clicks tab: the recorded clicks and drags of the active loadout: see them, change their times, reorder, delete, run. */
class ClicksFragment : Fragment() {

    private lateinit var binding: ActivityActionsBinding
    private lateinit var data: CustomActions
    private val shown = mutableListOf<CustomAction>()
    private lateinit var adapter: RowAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ActivityActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RowAdapter()
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ -> menu(position) }

        binding.btnShowPanel.setOnClickListener {
            val service = AutomationService.instance
            if (service == null) {
                toast("Turn on the accessibility service first")
            } else {
                service.showPanel()
                toast("Panel is showing. Open the game and use + Click and + Drag.")
            }
        }
        binding.btnRunOnce.setOnClickListener {
            saveRepeat()
            val service = AutomationService.instance
            when {
                service == null -> toast("Turn on the accessibility service first")
                data.actions.isEmpty() -> toast("No clicks or drags yet")
                else -> {
                    service.runCustomAfterDelay(SettingsStore.load(requireContext()).countdown.pick())
                    requireActivity().moveTaskToBack(true)
                }
            }
        }
        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete all recorded clicks and drags?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    data.actions.clear()
                    data.screenWidth = 0
                    data.screenHeight = 0
                    ActionStore.save(requireContext(), data)
                    AutomationService.instance?.hidePanelMarkers()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onPause() {
        super.onPause()
        if (::data.isInitialized) saveRepeat()
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun reload() {
        data = ActionStore.load(requireContext())
        binding.repeatInput.setText(data.repeat.toString())
        refresh()
    }

    private fun saveRepeat() {
        val n = binding.repeatInput.text.toString().toIntOrNull()?.coerceIn(1, 100000) ?: data.repeat
        data.repeat = n
        ActionStore.save(requireContext(), data)
    }

    private fun refresh() {
        shown.clear()
        shown.addAll(data.actions)
        adapter.notifyDataSetChanged()
        binding.summary.text =
            if (data.actions.isEmpty()) "No clicks or drags yet."
            else "${data.actions.size} action(s), recorded on a ${data.screenWidth} x ${data.screenHeight} screen"
    }

    private fun range(min: Long, max: Long) = if (max <= min) "$min ms" else "$min to $max ms"

    private fun menu(position: Int) {
        val a = data.actions.getOrNull(position) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Action ${position + 1}")
            .setItems(arrayOf("Change times", "Move up", "Move down", "Delete")) { _, which ->
                when (which) {
                    0 -> editTimes(a)
                    1 -> move(position, position - 1)
                    2 -> move(position, position + 1)
                    else -> {
                        data.actions.removeAt(position)
                        ActionStore.save(requireContext(), data)
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun move(from: Int, to: Int) {
        if (to < 0 || to >= data.actions.size) return
        val item = data.actions.removeAt(from)
        data.actions.add(to, item)
        ActionStore.save(requireContext(), data)
        refresh()
    }

    private fun editTimes(a: CustomAction) {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(label: String, value: Long): EditText {
            box.addView(TextView(requireContext()).apply { text = label; textSize = 12f })
            val input = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            box.addView(input)
            return input
        }
        val what = if (a.type == ActionType.CLICK) "Press length" else "Drag duration"
        val hMin = field("$what, from (ms)", a.holdMin)
        val hMax = field("$what, to (ms)", a.holdMax)
        val wMin = field("Wait after it, from (ms)", a.waitMin)
        val wMax = field("Wait after it, to (ms)", a.waitMax)

        AlertDialog.Builder(requireContext())
            .setTitle("Times in milliseconds")
            .setMessage("Same number in both boxes = fixed. A range = a random time in it every time.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val hm = (hMin.text.toString().toLongOrNull() ?: a.holdMin).coerceIn(1L, 60000L)
                val hx = (hMax.text.toString().toLongOrNull() ?: a.holdMax).coerceIn(1L, 60000L).coerceAtLeast(hm)
                val wm = (wMin.text.toString().toLongOrNull() ?: a.waitMin).coerceIn(0L, 600000L)
                val wx = (wMax.text.toString().toLongOrNull() ?: a.waitMax).coerceIn(0L, 600000L).coerceAtLeast(wm)
                a.holdMin = hm
                a.holdMax = hx
                a.waitMin = wm
                a.waitMax = wx
                ActionStore.save(requireContext(), data)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RowAdapter : ArrayAdapter<CustomAction>(requireContext(), 0, shown) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val a = shown[position]
            val dp = resources.displayMetrics.density
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            }
            val title = if (a.type == ActionType.CLICK) {
                "${position + 1}. Click at (${a.x1}, ${a.y1})"
            } else {
                "${position + 1}. Drag (${a.x1}, ${a.y1}) to (${a.x2}, ${a.y2})"
            }
            val whatName = if (a.type == ActionType.CLICK) "press" else "duration"
            box.addView(TextView(context).apply { text = title; textSize = 16f })
            box.addView(TextView(context).apply {
                text = "$whatName ${range(a.holdMin, a.holdMax)}, then wait ${range(a.waitMin, a.waitMax)}"
                textSize = 12f
            })
            return box
        }
    }
}

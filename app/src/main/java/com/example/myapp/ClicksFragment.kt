package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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

/**
 * The Clicks tab: the custom list of the active loadout (taps, swipes, holds, waits and the app's own
 * sort, merge and summon steps): see it, open a step's settings, reorder, delete, add steps, run it.
 */
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
                service.showClickWindow()
                toast("Click window is showing. Open the game and add taps, swipes and holds.")
            }
        }
        binding.btnAddStep.setOnClickListener { addStepDialog() }
        binding.btnRunOnce.setOnClickListener {
            saveRepeat()
            val service = AutomationService.instance
            when {
                service == null -> toast("Turn on the accessibility service first")
                data.actions.isEmpty() -> toast("The list is empty")
                else -> {
                    service.runCustomAfterDelay(SettingsStore.load(requireContext()).countdown.pick())
                    requireActivity().moveTaskToBack(true)
                }
            }
        }
        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete the whole list?")
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
        val n = binding.repeatInput.text.toString().toIntOrNull()?.coerceIn(1, 1_000_000) ?: data.repeat
        data.repeat = n
        ActionStore.save(requireContext(), data)
    }

    private fun refresh() {
        shown.clear()
        shown.addAll(data.actions)
        adapter.notifyDataSetChanged()
        binding.summary.text =
            if (data.actions.isEmpty()) "The list is empty."
            else "${data.actions.size} step(s)" +
                if (data.screenWidth > 0) ", positions recorded on a ${data.screenWidth} x ${data.screenHeight} screen" else ""
    }

    private fun range(min: Long, max: Long) = if (max <= min) "$min ms" else "$min to $max ms"

    private fun title(i: Int, a: CustomAction): String = when (a.type) {
        ActionType.CLICK -> "${i + 1}. Tap at (${a.x1}, ${a.y1})"
        ActionType.DRAG -> "${i + 1}. Swipe (${a.x1}, ${a.y1}) to (${a.x2}, ${a.y2})"
        ActionType.HOLD -> "${i + 1}. Hold at (${a.x1}, ${a.y1}) for ${range(a.holdMin, a.holdMax)}"
        ActionType.WAIT -> "${i + 1}. Wait ${range(a.holdMin, a.holdMax)}"
        ActionType.SORT -> "${i + 1}. Sort board"
        ActionType.MERGE -> "${i + 1}. Merge chain"
        ActionType.SUMMON -> "${i + 1}. Summon (hold until the grid is full)"
    }

    private fun detail(a: CustomAction): String = buildString {
        if (a.type == ActionType.CLICK) append("touch & hold ${range(a.holdMin, a.holdMax)}, ")
        if (a.type == ActionType.DRAG) append("swipe duration ${range(a.holdMin, a.holdMax)}, ")
        append("then wait ${range(a.waitMin, a.waitMax)}")
        if (a.repeat > 1) append(", x${a.repeat}")
        if (a.jitter > 0) append(", offset ${a.jitter} px")
    }

    private fun menu(position: Int) {
        if (data.actions.getOrNull(position) == null) return
        AlertDialog.Builder(requireContext())
            .setTitle("Step ${position + 1}")
            .setItems(arrayOf("Settings", "Move up", "Move down", "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(requireContext(), ActionSettingsActivity::class.java)
                            .putExtra(ActionSettingsActivity.EXTRA_INDEX, position)
                    )
                    1 -> move(position, position - 1)
                    2 -> move(position, position + 1)
                    else -> {
                        data.actions.removeAt(position)
                        ActionStore.save(requireContext(), data)
                        AutomationService.instance?.refreshMarkers()
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
        AutomationService.instance?.refreshMarkers()
        refresh()
    }

    /** Taps, swipes and holds need real spots, so they are added with the click window; these steps need none. */
    private fun addStepDialog() {
        val kinds = listOf(
            ActionType.SORT to "Sort board",
            ActionType.MERGE to "Merge chain",
            ActionType.SUMMON to "Summon (hold until the grid is full)",
            ActionType.WAIT to "Wait"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Add a step to the end of the list")
            .setItems(kinds.map { it.second }.toTypedArray()) { _, which ->
                val type = kinds[which].first
                val step = if (type == ActionType.WAIT) {
                    CustomAction(type, 0, 0, 0, 0, 1000L, 1000L, 0L, 0L)
                } else {
                    CustomAction(type, 0, 0, 0, 0, 0L, 0L, 500L, 500L)
                }
                data.actions.add(step)
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
            box.addView(TextView(context).apply {
                text = title(position, a)
                textSize = 16f
            })
            box.addView(TextView(context).apply {
                text = detail(a)
                textSize = 12f
            })
            return box
        }
    }
}

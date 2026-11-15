package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.LayoutInflater
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.myapp.databinding.ActivitySettingsBinding
import com.example.myapp.storage.AppSettings
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.MsRange
import com.example.myapp.storage.SettingsStore
import com.example.myapp.storage.StepConfig
import com.example.myapp.storage.StepType

/** The Loadout tab: which grid and click points to use, times, loop options and the order of the loop steps (for the active loadout). */
class LoadoutFragment : Fragment() {

    private lateinit var binding: ActivitySettingsBinding
    private val steps = mutableListOf<StepConfig>()
    private var loaded = false

    private val none = "(none)"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ActivitySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        binding.btnTestExtra.setOnClickListener {
            save()
            val service = AutomationService.instance
            val point = binding.extraSpinner.selectedItem as? String
            when {
                service == null -> toast("Turn on the accessibility service first")
                point == null || point == none -> toast("Choose an extra click point first")
                else -> {
                    service.tapPointAfterDelay(point, SettingsStore.load(requireContext()).countdown.pick())
                    requireActivity().moveTaskToBack(true)
                }
            }
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Fills every box from the active loadout (also after another loadout was chosen). */
    private fun reload() {
        val s = SettingsStore.load(requireContext())

        fillSpinner(binding.gridSpinner, GridStorage.listProfiles(requireContext()), s.grid, false)
        fillSpinner(binding.summonSpinner, ClickPointStorage.list(requireContext()), s.summonPoint, true)
        fillSpinner(binding.extraSpinner, ClickPointStorage.list(requireContext()), s.extraPoint, true)

        showRange(binding.dragMinInput, binding.dragMaxInput, s.drag)
        showRange(binding.pauseMinInput, binding.pauseMaxInput, s.pause)
        showRange(binding.holdMinInput, binding.holdMaxInput, s.maxHold)
        showRange(binding.loopPauseMinInput, binding.loopPauseMaxInput, s.loopPause)
        showRange(binding.countdownMinInput, binding.countdownMaxInput, s.countdown)
        binding.loopsInput.setText(s.loops.toString())
        binding.sortPassesInput.setText(s.sortPasses.toString())
        binding.sortMovesInput.setText(s.sortMovesPerPass.toString())
        binding.checkSwap.isChecked = s.swap
        binding.checkVerify.isChecked = s.verify
        binding.checkReread.isChecked = s.rereadMerge
        binding.checkPreview.isChecked = s.previewOnly

        steps.clear()
        steps.addAll(s.steps)
        renderSteps()
        loaded = true
    }

    override fun onPause() {
        super.onPause()
        if (loaded) save()
    }

    private fun showRange(minInput: EditText, maxInput: EditText, r: MsRange) {
        minInput.setText(r.min.toString())
        maxInput.setText(r.max.toString())
    }

    /** A blank or invalid box keeps the old value; the second box can't be below the first. */
    private fun readRange(minInput: EditText, maxInput: EditText, old: MsRange, lo: Long, hi: Long): MsRange {
        val min = minInput.text.toString().toLongOrNull()?.coerceIn(lo, hi) ?: old.min
        val max = (maxInput.text.toString().toLongOrNull()?.coerceIn(lo, hi) ?: old.max).coerceAtLeast(min)
        return MsRange(min, max)
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun fillSpinner(spinner: Spinner, names: List<String>, selected: String, allowNone: Boolean) {
        val items = if (allowNone) listOf(none) + names else names
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
        val index = items.indexOf(selected)
        if (index >= 0) spinner.setSelection(index)
    }

    private fun spinnerValue(spinner: Spinner): String {
        val v = spinner.selectedItem as? String ?: return ""
        return if (v == none) "" else v
    }

    private fun label(type: StepType): String = when (type) {
        StepType.MERGE -> "Merge chain"
        StepType.SORT -> "Sort board"
        StepType.SUMMON -> "Summon (hold until grid full)"
        StepType.CLICK -> "Extra click (taps the extra point once)"
        StepType.CUSTOM -> "Custom clicks & drags (the ones you recorded)"
    }

    /** Draws one row per step: tick box, then move up / move down. */
    private fun renderSteps() {
        val box = binding.stepsContainer
        box.removeAllViews()
        steps.forEachIndexed { i, step ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val check = CheckBox(requireContext()).apply {
                text = "${i + 1}. ${label(step.type)}"
                isChecked = step.enabled
                setOnCheckedChangeListener { _, checked -> steps[i] = StepConfig(step.type, checked) }
            }
            row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val up = Button(requireContext()).apply {
                text = "▲"
                isEnabled = i > 0
                setOnClickListener { move(i, i - 1) }
            }
            val down = Button(requireContext()).apply {
                text = "▼"
                isEnabled = i < steps.size - 1
                setOnClickListener { move(i, i + 1) }
            }
            row.addView(up)
            row.addView(down)
            box.addView(row)
        }
    }

    private fun move(from: Int, to: Int) {
        val item = steps.removeAt(from)
        steps.add(to, item)
        renderSteps()
    }

    private fun save() {
        val old = SettingsStore.load(requireContext())
        val s = AppSettings(
            grid = spinnerValue(binding.gridSpinner),
            summonPoint = spinnerValue(binding.summonSpinner),
            extraPoint = spinnerValue(binding.extraSpinner),
            drag = readRange(binding.dragMinInput, binding.dragMaxInput, old.drag, 30L, 5000L),
            pause = readRange(binding.pauseMinInput, binding.pauseMaxInput, old.pause, 0L, 60000L),
            sortPasses = binding.sortPassesInput.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: old.sortPasses,
            sortMovesPerPass = binding.sortMovesInput.text.toString().toIntOrNull()?.coerceIn(0, 500) ?: old.sortMovesPerPass,
            swap = binding.checkSwap.isChecked,
            verify = binding.checkVerify.isChecked,
            rereadMerge = binding.checkReread.isChecked,
            previewOnly = binding.checkPreview.isChecked,
            maxHold = readRange(binding.holdMinInput, binding.holdMaxInput, old.maxHold, 500L, 600000L),
            loops = binding.loopsInput.text.toString().toIntOrNull()?.coerceIn(0, 100000) ?: old.loops,
            loopPause = readRange(binding.loopPauseMinInput, binding.loopPauseMaxInput, old.loopPause, 0L, 600000L),
            countdown = readRange(binding.countdownMinInput, binding.countdownMaxInput, old.countdown, 0L, 60000L),
            steps = steps.toList()
        )
        SettingsStore.save(requireContext(), s)
    }
}

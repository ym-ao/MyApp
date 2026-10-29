package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivitySettingsBinding
import com.example.myapp.storage.AppSettings
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.SettingsStore
import com.example.myapp.storage.StepConfig
import com.example.myapp.storage.StepType

/** Loop settings: which grid and click points to use, speeds, and the order of the loop steps. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val steps = mutableListOf<StepConfig>()
    private var loaded = false

    private val none = "(none)"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val s = SettingsStore.load(this)

        fillSpinner(binding.gridSpinner, GridStorage.listProfiles(this), s.grid, false)
        fillSpinner(binding.summonSpinner, ClickPointStorage.list(this), s.summonPoint, true)
        fillSpinner(binding.extraSpinner, ClickPointStorage.list(this), s.extraPoint, true)

        binding.dragMsInput.setText(s.dragMs.toString())
        binding.pauseMsInput.setText(s.pauseMs.toString())
        binding.maxHoldInput.setText(s.maxHoldSeconds.toString())
        binding.loopsInput.setText(s.loops.toString())
        binding.loopPauseInput.setText(s.loopPauseMs.toString())
        binding.checkSwap.isChecked = s.swap
        binding.checkVerify.isChecked = s.verify
        binding.checkPreview.isChecked = s.previewOnly

        steps.clear()
        steps.addAll(s.steps)
        renderSteps()

        binding.btnTestExtra.setOnClickListener {
            save()
            val service = AutomationService.instance
            val point = binding.extraSpinner.selectedItem as? String
            when {
                service == null -> toast("Turn on the accessibility service first")
                point == null || point == none -> toast("Choose an extra click point first")
                else -> {
                    service.tapPointAfterDelay(point, 5000L)
                    moveTaskToBack(true)
                }
            }
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        binding.btnDone.setOnClickListener { finish() }
        loaded = true
    }

    override fun onPause() {
        super.onPause()
        if (loaded) save()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun fillSpinner(spinner: Spinner, names: List<String>, selected: String, allowNone: Boolean) {
        val items = if (allowNone) listOf(none) + names else names
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
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
    }

    /** Draws one row per step: tick box, then move up / move down. */
    private fun renderSteps() {
        val box = binding.stepsContainer
        box.removeAllViews()
        steps.forEachIndexed { i, step ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val check = CheckBox(this).apply {
                text = "${i + 1}. ${label(step.type)}"
                isChecked = step.enabled
                setOnCheckedChangeListener { _, checked -> steps[i] = StepConfig(step.type, checked) }
            }
            row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val up = Button(this).apply {
                text = "▲"
                isEnabled = i > 0
                setOnClickListener { move(i, i - 1) }
            }
            val down = Button(this).apply {
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
        val old = SettingsStore.load(this)
        val s = AppSettings(
            grid = spinnerValue(binding.gridSpinner),
            summonPoint = spinnerValue(binding.summonSpinner),
            extraPoint = spinnerValue(binding.extraSpinner),
            dragMs = binding.dragMsInput.text.toString().toLongOrNull()?.coerceIn(30L, 2000L) ?: old.dragMs,
            pauseMs = binding.pauseMsInput.text.toString().toLongOrNull()?.coerceIn(0L, 5000L) ?: old.pauseMs,
            swap = binding.checkSwap.isChecked,
            verify = binding.checkVerify.isChecked,
            previewOnly = binding.checkPreview.isChecked,
            maxHoldSeconds = binding.maxHoldInput.text.toString().toIntOrNull()?.coerceIn(5, 300) ?: old.maxHoldSeconds,
            loops = binding.loopsInput.text.toString().toIntOrNull()?.coerceIn(0, 100000) ?: old.loops,
            loopPauseMs = binding.loopPauseInput.text.toString().toLongOrNull()?.coerceIn(0L, 60000L) ?: old.loopPauseMs,
            steps = steps.toList()
        )
        SettingsStore.save(this, s)
    }
}

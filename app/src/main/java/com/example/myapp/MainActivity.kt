package com.example.myapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.automation.BoardAction
import com.example.myapp.databinding.ActivityMainBinding
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.PlanStore
import com.example.myapp.storage.SettingsStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnSetupGrid.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        binding.btnSetClickPoint.setOnClickListener {
            startActivity(Intent(this, ClickPointActivity::class.java))
        }
        binding.btnLibrary.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnViewResult.setOnClickListener {
            startActivity(Intent(this, ReadResultActivity::class.java))
        }

        binding.btnShowPanel.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.showPanel()
            Toast.makeText(this, "Panel is showing. Open the game to use it.", Toast.LENGTH_LONG).show()
        }
        binding.btnStartLoop.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.startLoopAfterDelay(5000L)
            moveTaskToBack(true)
        }
        binding.btnMerge.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.runBoardAction(BoardAction.MERGE, 5000L)
            moveTaskToBack(true)
        }
        binding.btnSort.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.runBoardAction(BoardAction.SORT, 5000L)
            moveTaskToBack(true)
        }
        binding.btnHoldSummon.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.holdSummonAfterDelay(5000L)
            moveTaskToBack(true)
        }
        binding.btnTestTap.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            val point = SettingsStore.load(this).summonPoint
            if (point.isBlank()) {
                Toast.makeText(this, "Choose the summon click point in Loop Settings first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service.tapPointAfterDelay(point, 5000L)
            moveTaskToBack(true)
        }
        binding.btnReadGrid.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Toast.makeText(this, "Reading the screen needs Android 11 or newer", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val grid = SettingsStore.load(this).grid
            if (grid.isBlank()) {
                Toast.makeText(this, "Choose a grid in Loop Settings first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service.readGridAfterDelay(grid, 5000L)
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        fillInDefaults()
        showPendingPlan()
    }

    private fun needService(): AutomationService? {
        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(this, "Turn on the accessibility service first", Toast.LENGTH_LONG).show()
        }
        return service
    }

    /** First run: pick the only grid / a point called "summon" so Loop Settings starts filled in. */
    private fun fillInDefaults() {
        val s = SettingsStore.load(this)
        val grids = GridStorage.listProfiles(this)
        val points = ClickPointStorage.list(this)
        var changed = s
        if ((s.grid.isBlank() || s.grid !in grids) && grids.isNotEmpty()) {
            changed = changed.copy(grid = grids.first())
        }
        if ((s.summonPoint.isBlank() || s.summonPoint !in points) && points.isNotEmpty()) {
            changed = changed.copy(summonPoint = if ("summon" in points) "summon" else points.first())
        }
        if (changed != s) SettingsStore.save(this, changed)
    }

    private fun refreshStatus() {
        binding.statusText.text = if (AutomationService.isRunning()) {
            "Accessibility service: ON"
        } else {
            val extra = if (Build.VERSION.SDK_INT >= 33) {
                "\nAndroid 13+: if the switch is greyed out, open Settings > Apps > MyApp, " +
                    "tap the menu (three dots) and choose Allow restricted settings."
            } else ""
            "Accessibility service: OFF. Tap the button below, find \"MyApp automation\" and turn it on.$extra"
        }
    }

    /** If a "preview only" run left a plan behind, show it once. */
    private fun showPendingPlan() {
        val plan = PlanStore.takePending(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Plan (nothing was moved)")
            .setMessage(plan)
            .setPositiveButton("OK", null)
            .show()
    }
}

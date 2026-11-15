package com.example.myapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.myapp.automation.BoardAction
import com.example.myapp.databinding.FragmentRunBinding
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.LoadoutStore
import com.example.myapp.storage.PlanStore
import com.example.myapp.storage.SettingsStore

/** The Run tab: service status, loadouts, the floating panel, the loop, and one-piece tests. */
class RunFragment : Fragment() {

    private lateinit var binding: FragmentRunBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentRunBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnLoadoutNew.setOnClickListener { newLoadout() }
        binding.btnLoadoutRename.setOnClickListener { renameLoadout() }
        binding.btnLoadoutDelete.setOnClickListener { deleteLoadout() }

        binding.btnShowPanel.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.showPanel()
            toast("Panel is showing. Open the game to use it.")
        }
        binding.btnStartLoop.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.startLoopAfterDelay(countdown())
            requireActivity().moveTaskToBack(true)
        }
        binding.btnMerge.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.runBoardAction(BoardAction.MERGE, countdown())
            requireActivity().moveTaskToBack(true)
        }
        binding.btnSort.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.runBoardAction(BoardAction.SORT, countdown())
            requireActivity().moveTaskToBack(true)
        }
        binding.btnHoldSummon.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            service.holdSummonAfterDelay(countdown())
            requireActivity().moveTaskToBack(true)
        }
        binding.btnTestTap.setOnClickListener {
            val service = needService() ?: return@setOnClickListener
            val point = SettingsStore.load(requireContext()).summonPoint
            if (point.isBlank()) {
                toast("Choose the summon click point in the Loadout tab first")
                return@setOnClickListener
            }
            service.tapPointAfterDelay(point, countdown())
            requireActivity().moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        fillInDefaults()
        refreshLoadouts()
        showPendingPlan()
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    /** The wait before a test starts, so there is time to switch to the game (milliseconds). */
    private fun countdown(): Long = SettingsStore.load(requireContext()).countdown.pick()

    private fun needService(): AutomationService? {
        val service = AutomationService.instance
        if (service == null) toast("Turn on the accessibility service first")
        return service
    }

    // ---- loadouts ----

    private fun refreshLoadouts() {
        val ctx = requireContext()
        val list = LoadoutStore.list(ctx)
        val active = LoadoutStore.activeId(ctx)

        binding.loadoutSpinner.onItemSelectedListener = null
        binding.loadoutSpinner.adapter =
            ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
        binding.loadoutSpinner.setSelection(list.indexOfFirst { it.id == active }.coerceAtLeast(0))
        binding.loadoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = list.getOrNull(position) ?: return
                if (chosen.id == LoadoutStore.activeId(ctx)) return
                if (AutomationService.instance?.isBusy() == true) {
                    toast("Stop the running job first, then switch loadout")
                    refreshLoadouts()
                    return
                }
                LoadoutStore.setActive(ctx, chosen.id)
                fillInDefaults()
                AutomationService.instance?.refreshPanelLoadout()
                toast("Loadout: ${chosen.name}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun newLoadout() {
        val ctx = requireContext()
        if (AutomationService.instance?.isBusy() == true) {
            toast("Stop the running job first")
            return
        }
        val input = EditText(ctx).apply {
            hint = "Name"
            setText("Loadout ${LoadoutStore.list(ctx).size + 1}")
        }
        AlertDialog.Builder(ctx)
            .setTitle("New loadout (a copy of the current one)")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val made = LoadoutStore.create(ctx, input.text.toString(), LoadoutStore.activeId(ctx))
                LoadoutStore.setActive(ctx, made.id)
                AutomationService.instance?.refreshPanelLoadout()
                refreshLoadouts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameLoadout() {
        val ctx = requireContext()
        val input = EditText(ctx).apply { setText(LoadoutStore.activeName(ctx)) }
        AlertDialog.Builder(ctx)
            .setTitle("Rename this loadout")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                LoadoutStore.rename(ctx, LoadoutStore.activeId(ctx), input.text.toString())
                AutomationService.instance?.refreshPanelLoadout()
                refreshLoadouts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteLoadout() {
        val ctx = requireContext()
        if (AutomationService.instance?.isBusy() == true) {
            toast("Stop the running job first")
            return
        }
        if (LoadoutStore.list(ctx).size <= 1) {
            toast("This is the only loadout, so it can't be deleted")
            return
        }
        val name = LoadoutStore.activeName(ctx)
        AlertDialog.Builder(ctx)
            .setTitle("Delete '$name'?")
            .setMessage("Its settings, step order and custom clicks are deleted. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                LoadoutStore.delete(ctx, LoadoutStore.activeId(ctx))
                AutomationService.instance?.hidePanelMarkers()
                AutomationService.instance?.refreshPanelLoadout()
                refreshLoadouts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** First run of a loadout: pick the only grid / a point called "summon" so its settings start filled in. */
    private fun fillInDefaults() {
        val ctx = requireContext()
        val s = SettingsStore.load(ctx)
        val grids = GridStorage.listProfiles(ctx)
        val points = ClickPointStorage.list(ctx)
        var changed = s
        if ((s.grid.isBlank() || s.grid !in grids) && grids.isNotEmpty()) {
            changed = changed.copy(grid = grids.first())
        }
        if ((s.summonPoint.isBlank() || s.summonPoint !in points) && points.isNotEmpty()) {
            changed = changed.copy(summonPoint = if ("summon" in points) "summon" else points.first())
        }
        if (changed != s) SettingsStore.save(ctx, changed)
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
        val plan = PlanStore.takePending(requireContext()) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Plan (nothing was moved)")
            .setMessage(plan)
            .setPositiveButton("OK", null)
            .show()
    }
}

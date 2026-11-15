package com.example.myapp

import android.graphics.Typeface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.myapp.databinding.FragmentSetupBinding
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.SettingsStore

/** The Setup tab: set up grids and click points, read the board, and view, choose or delete what is saved. */
class SetupFragment : Fragment() {

    private enum class Kind { HEADER, GRID, POINT, NOTE }

    private class Row(val kind: Kind, val name: String, val title: String, val detail: String)

    private lateinit var binding: FragmentSetupBinding
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RowAdapter()
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            when (row.kind) {
                Kind.GRID -> gridMenu(row.name)
                Kind.POINT -> pointMenu(row.name)
                else -> {}
            }
        }
        binding.btnSetupGrid.setOnClickListener {
            startActivity(Intent(requireContext(), CalibrationActivity::class.java))
        }
        binding.btnSetClickPoint.setOnClickListener {
            startActivity(Intent(requireContext(), ClickPointActivity::class.java))
        }
        binding.btnViewResult.setOnClickListener {
            startActivity(Intent(requireContext(), ReadResultActivity::class.java))
        }
        binding.btnReadGrid.setOnClickListener {
            val service = AutomationService.instance
            if (service == null) {
                Toast.makeText(requireContext(), "Turn on the accessibility service first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Toast.makeText(requireContext(), "Reading the screen needs Android 11 or newer", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val settings = SettingsStore.load(requireContext())
            if (settings.grid.isBlank()) {
                Toast.makeText(requireContext(), "Choose a grid in the Loadout tab first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service.readGridAfterDelay(settings.grid, settings.countdown.pick())
            requireActivity().moveTaskToBack(true)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val settings = SettingsStore.load(requireContext())
        rows.clear()

        rows.add(Row(Kind.HEADER, "", "Grids", ""))
        val grids = GridStorage.listProfiles(requireContext())
        if (grids.isEmpty()) rows.add(Row(Kind.NOTE, "", "(none saved yet)", ""))
        for (name in grids) {
            val g = GridStorage.load(requireContext(), name)
            val detail = if (g == null) "can't be opened" else
                "${g.allBoxes().size} boxes, ${g.regions.size} region(s), calibrated on ${g.screenWidth} x ${g.screenHeight}"
            val tag = if (name == settings.grid) "   (current)" else ""
            rows.add(Row(Kind.GRID, name, name + tag, detail))
        }

        rows.add(Row(Kind.HEADER, "", "Click points", ""))
        val points = ClickPointStorage.list(requireContext())
        if (points.isEmpty()) rows.add(Row(Kind.NOTE, "", "(none saved yet)", ""))
        for (name in points) {
            val p = ClickPointStorage.load(requireContext(), name)
            val detail = if (p == null) "can't be opened" else
                "at ${p.x}, ${p.y} on ${p.screenWidth} x ${p.screenHeight}"
            val tags = buildString {
                if (name == settings.summonPoint) append("   (summon)")
                if (name == settings.extraPoint) append("   (extra click)")
            }
            rows.add(Row(Kind.POINT, name, name + tags, detail))
        }
        adapter.notifyDataSetChanged()
    }

    private fun gridMenu(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(arrayOf("Use as the current grid", "Delete")) { _, which ->
                if (which == 0) {
                    SettingsStore.save(requireContext(), SettingsStore.load(requireContext()).copy(grid = name))
                    refresh()
                } else {
                    confirmDelete("Delete grid '$name'?") {
                        GridStorage.delete(requireContext(), name)
                        val s = SettingsStore.load(requireContext())
                        if (s.grid == name) SettingsStore.save(requireContext(), s.copy(grid = ""))
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pointMenu(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(arrayOf("Use as the summon point", "Use as the extra click point", "Delete")) { _, which ->
                val s = SettingsStore.load(requireContext())
                when (which) {
                    0 -> {
                        SettingsStore.save(requireContext(), s.copy(summonPoint = name))
                        refresh()
                    }
                    1 -> {
                        SettingsStore.save(requireContext(), s.copy(extraPoint = name))
                        refresh()
                    }
                    else -> confirmDelete("Delete click point '$name'?") {
                        ClickPointStorage.delete(requireContext(), name)
                        val latest = SettingsStore.load(requireContext())
                        SettingsStore.save(
                            requireContext(),
                            latest.copy(
                                summonPoint = if (latest.summonPoint == name) "" else latest.summonPoint,
                                extraPoint = if (latest.extraPoint == name) "" else latest.extraPoint
                            )
                        )
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(message)
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RowAdapter : ArrayAdapter<Row>(requireContext(), 0, rows) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val dp = resources.displayMetrics.density
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            }
            box.addView(TextView(context).apply {
                text = row.title
                when (row.kind) {
                    Kind.HEADER -> {
                        textSize = 18f
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    Kind.NOTE -> textSize = 14f
                    else -> textSize = 16f
                }
            })
            if (row.detail.isNotEmpty()) {
                box.addView(TextView(context).apply {
                    text = row.detail
                    textSize = 12f
                })
            }
            return box
        }

        override fun isEnabled(position: Int): Boolean =
            rows[position].kind == Kind.GRID || rows[position].kind == Kind.POINT
    }
}

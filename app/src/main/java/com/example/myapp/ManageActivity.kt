package com.example.myapp

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivityManageBinding
import com.example.myapp.storage.ClickPointStorage
import com.example.myapp.storage.GridStorage
import com.example.myapp.storage.SettingsStore

/** View, choose and delete saved grids and click points. */
class ManageActivity : AppCompatActivity() {

    private enum class Kind { HEADER, GRID, POINT, NOTE }

    private class Row(val kind: Kind, val name: String, val title: String, val detail: String)

    private lateinit var binding: ActivityManageBinding
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val settings = SettingsStore.load(this)
        rows.clear()

        rows.add(Row(Kind.HEADER, "", "Grids", ""))
        val grids = GridStorage.listProfiles(this)
        if (grids.isEmpty()) rows.add(Row(Kind.NOTE, "", "(none saved yet)", ""))
        for (name in grids) {
            val g = GridStorage.load(this, name)
            val detail = if (g == null) "can't be opened" else
                "${g.allBoxes().size} boxes, ${g.regions.size} region(s), calibrated on ${g.screenWidth} x ${g.screenHeight}"
            val tag = if (name == settings.grid) "   (current)" else ""
            rows.add(Row(Kind.GRID, name, name + tag, detail))
        }

        rows.add(Row(Kind.HEADER, "", "Click points", ""))
        val points = ClickPointStorage.list(this)
        if (points.isEmpty()) rows.add(Row(Kind.NOTE, "", "(none saved yet)", ""))
        for (name in points) {
            val p = ClickPointStorage.load(this, name)
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
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Use as the current grid", "Delete")) { _, which ->
                if (which == 0) {
                    SettingsStore.save(this, SettingsStore.load(this).copy(grid = name))
                    refresh()
                } else {
                    confirmDelete("Delete grid '$name'?") {
                        GridStorage.delete(this, name)
                        val s = SettingsStore.load(this)
                        if (s.grid == name) SettingsStore.save(this, s.copy(grid = ""))
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pointMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Use as the summon point", "Use as the extra click point", "Delete")) { _, which ->
                val s = SettingsStore.load(this)
                when (which) {
                    0 -> {
                        SettingsStore.save(this, s.copy(summonPoint = name))
                        refresh()
                    }
                    1 -> {
                        SettingsStore.save(this, s.copy(extraPoint = name))
                        refresh()
                    }
                    else -> confirmDelete("Delete click point '$name'?") {
                        ClickPointStorage.delete(this, name)
                        val latest = SettingsStore.load(this)
                        SettingsStore.save(
                            this,
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
        AlertDialog.Builder(this)
            .setTitle(message)
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RowAdapter : ArrayAdapter<Row>(this@ManageActivity, 0, rows) {

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

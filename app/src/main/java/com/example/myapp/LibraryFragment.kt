package com.example.myapp

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.myapp.databinding.ActivityLibraryBinding
import com.example.myapp.storage.TileEntry
import com.example.myapp.storage.TileLibrary

/** The picture library: give remembered tile pictures a level and lock them. */
class LibraryFragment : Fragment() {

    private lateinit var binding: ActivityLibraryBinding
    private val shown = mutableListOf<TileEntry>()
    private lateinit var adapter: EntryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ActivityLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = EntryAdapter()
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ -> edit(shown[position]) }

        binding.btnLockAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Lock all with a level?")
                .setMessage("Only do this after checking the levels. The reader's guesses can be wrong, and locked pictures are trusted from then on.")
                .setPositiveButton("Lock") { _, _ ->
                    TileLibrary.entries(requireContext()).forEach { if (it.value != null) it.locked = true }
                    TileLibrary.markChanged(requireContext())
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnDeleteUnlocked.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete all unlocked pictures?")
                .setMessage("Locked pictures stay. Unlocked ones will be added again the next time the board is read.")
                .setPositiveButton("Delete") { _, _ ->
                    TileLibrary.entries(requireContext()).filter { !it.locked }.forEach { TileLibrary.delete(requireContext(), it) }
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all = TileLibrary.entries(requireContext())
        shown.clear()
        // pictures that still need a level first, then highest level first
        shown.addAll(all.sortedWith(compareBy<TileEntry>({ it.value != null }, { -(it.value ?: 0) })))
        adapter.notifyDataSetChanged()
        val locked = all.count { it.locked }
        val needs = all.count { it.value == null }
        binding.summary.text =
            "${all.size} pictures: $locked locked, $needs need a level, ${all.size - locked - needs} not confirmed yet"
    }

    private fun scaled(bitmap: Bitmap, factor: Int): Bitmap =
        Bitmap.createScaledBitmap(bitmap, bitmap.width * factor, bitmap.height * factor, false)

    private fun edit(entry: TileEntry) {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        box.addView(ImageView(requireContext()).apply {
            setImageBitmap(scaled(entry.thumb, 6))
            adjustViewBounds = true
        })
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Level (1 to 99)"
            setText((entry.value ?: entry.hint)?.toString() ?: "")
        }
        box.addView(input)
        val lock = CheckBox(requireContext()).apply {
            text = "Lock this level (the reader can't change it)"
            isChecked = entry.locked || entry.value == null
        }
        box.addView(lock)

        AlertDialog.Builder(requireContext())
            .setTitle("Level for this picture")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val level = input.text.toString().toIntOrNull()
                if (level == null || level !in 1..99) {
                    Toast.makeText(requireContext(), "Enter a level from 1 to 99", Toast.LENGTH_LONG).show()
                } else {
                    entry.value = level
                    entry.hint = null
                    entry.locked = lock.isChecked
                    TileLibrary.markChanged(requireContext())
                    refresh()
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                TileLibrary.delete(requireContext(), entry)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class EntryAdapter :
        ArrayAdapter<TileEntry>(requireContext(), 0, shown) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = shown[position]
            val dp = resources.displayMetrics.density

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            }
            val image = ImageView(context).apply {
                setImageBitmap(scaled(entry.thumb, 3))
            }
            row.addView(image, LinearLayout.LayoutParams((108 * dp / 2).toInt(), (90 * dp / 2).toInt()))

            val text = TextView(context).apply {
                textSize = 16f
                setPadding((12 * dp).toInt(), 0, 0, 0)
                text = when {
                    entry.value == null && entry.hint != null ->
                        "Needs a level (reader guessed ${entry.hint})"
                    entry.value == null -> "Needs a level"
                    entry.locked -> "Level ${entry.value}   (locked)"
                    else -> "Level ${entry.value}   (not confirmed yet)"
                }
            }
            row.addView(text)
            return row
        }
    }
}

package com.example.myapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.myapp.databinding.FragmentCodeBinding

/**
 * The Code tab: read-only copies of the files that decide how sorting, merging and reading work,
 * with a Copy button, so they can be pasted somewhere (for example into a chat).
 */
class CodeFragment : Fragment() {

    private lateinit var binding: FragmentCodeBinding

    private val files = listOf(
        "BoardPlanner.kt" to "Sort and merge rules (how the plan is worked out)",
        "AutomationService.kt" to "Running it: merge chain, sort passes, loop, floating windows",
        "GridReader.kt" to "Reading the board (empty squares, library, text reading)",
        "TileVision.kt" to "Finding the number in a box and comparing pictures",
        "TileLibrary.kt" to "The picture library"
    )

    private var currentName = ""
    private var currentText = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fileSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, files.map { "${it.first}: ${it.second}" }
        )
        binding.fileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                show(files[position].first)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnCopy.setOnClickListener {
            if (currentText.isEmpty()) {
                Toast.makeText(requireContext(), "Nothing to copy", Toast.LENGTH_SHORT).show()
            } else {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(currentName, currentText))
                Toast.makeText(
                    requireContext(), "Copied $currentName (${currentText.length} characters)", Toast.LENGTH_LONG
                ).show()
            }
        }
        show(files[0].first)
    }

    private fun show(name: String) {
        currentName = name
        currentText = try {
            requireContext().assets.open("code/$name.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Couldn't open $name: ${e.message}"
        }
        binding.codeText.text = currentText
    }
}

package com.example.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivityCalibrationBinding
import com.example.myapp.model.GridProfile
import com.example.myapp.model.GridRegion
import com.example.myapp.storage.GridStorage
import com.example.myapp.ui.CalibrationMode

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private var currentBitmap: Bitmap? = null
    private val savedRegions = mutableListOf<GridRegion>()

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadImage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.overlay.imageView = binding.screenshotImage
        binding.regionName.setText("main_board")

        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }

        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val fast = checkedId == R.id.radioFast
            binding.overlay.mode = if (fast) CalibrationMode.FAST_GRID else CalibrationMode.MANUAL
            binding.fastGridControls.visibility = if (fast) View.VISIBLE else View.GONE
        }

        binding.btnGenerateGrid.setOnClickListener {
            val rows = binding.rowsInput.text.toString().toIntOrNull()
            val cols = binding.colsInput.text.toString().toIntOrNull()
            if (rows == null || cols == null) {
                Toast.makeText(this, "Enter rows and columns", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = binding.overlay.generateFastGrid(rows, cols)
            if (!ok) {
                Toast.makeText(this, "Tap top-left and bottom-right cells first", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }

        binding.btnUndo.setOnClickListener { binding.overlay.undoLast(); updateStatus() }
        binding.btnClear.setOnClickListener { binding.overlay.clearAll(); updateStatus() }

        binding.btnAddRegion.setOnClickListener { commitCurrentRegion() }
        binding.btnSave.setOnClickListener { saveProfile() }

        updateStatus()
        updateRegionsSummary()
    }

    private fun loadImage(uri: Uri) {
        val input = contentResolver.openInputStream(uri) ?: return
        val bitmap = BitmapFactory.decodeStream(input)
        input.close()

        currentBitmap = bitmap
        binding.screenshotImage.setImageBitmap(bitmap)
        binding.overlay.fullReset()
        savedRegions.clear()
        updateStatus()
        updateRegionsSummary()
        Toast.makeText(
            this,
            "Mark this region, then tap 'Add Region + Start Next' for the next section of the board",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateStatus() {
        binding.statusText.text = "Boxes in this region: ${binding.overlay.boxes.size}"
    }

    private fun updateRegionsSummary() {
        binding.regionsSummary.text = if (savedRegions.isEmpty()) {
            "Saved regions: (none yet)"
        } else {
            "Saved regions: " + savedRegions.joinToString(", ") { "${it.name} (${it.boxes.size})" }
        }
    }

    /** Moves the overlay's current boxes into savedRegions under the current region name. */
    private fun commitCurrentRegion(): Boolean {
        val name = binding.regionName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name for this region", Toast.LENGTH_SHORT).show()
            return false
        }
        if (binding.overlay.boxes.isEmpty()) {
            Toast.makeText(this, "Mark at least one box in this region first", Toast.LENGTH_SHORT).show()
            return false
        }
        savedRegions.add(GridRegion(name, binding.overlay.boxes.toMutableList()))
        binding.overlay.resetForNewRegion()
        updateStatus()
        updateRegionsSummary()
        Toast.makeText(this, "Region '$name' added. Name and mark the next region.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun saveProfile() {
        val bmp = currentBitmap
        val profileName = binding.profileName.text.toString().trim()

        if (bmp == null) {
            Toast.makeText(this, "Load a screenshot first", Toast.LENGTH_SHORT).show()
            return
        }
        if (profileName.isEmpty()) {
            Toast.makeText(this, "Enter a grid profile name", Toast.LENGTH_SHORT).show()
            return
        }

        // If there are unsaved boxes sitting in the current region, commit them first.
        if (binding.overlay.boxes.isNotEmpty()) {
            if (!commitCurrentRegion()) return
        }

        if (savedRegions.isEmpty()) {
            Toast.makeText(this, "Mark at least one region first", Toast.LENGTH_SHORT).show()
            return
        }

        val profile = GridProfile(
            name = profileName,
            screenWidth = bmp.width,
            screenHeight = bmp.height,
            regions = savedRegions.toMutableList()
        )
        GridStorage.save(this, profile)

        val totalBoxes = profile.allBoxes().size
        Toast.makeText(
            this,
            "Saved '$profileName': ${profile.regions.size} region(s), $totalBoxes box(es) total",
            Toast.LENGTH_LONG
        ).show()
    }
}

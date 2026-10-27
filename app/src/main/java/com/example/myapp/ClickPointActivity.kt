package com.example.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivityClickPointBinding
import com.example.myapp.model.ClickPoint
import com.example.myapp.storage.ClickPointStorage

/** Lets you mark one spot on a screenshot (e.g. the summon button) and save it by name. */
class ClickPointActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClickPointBinding
    private var currentBitmap: Bitmap? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadImage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClickPointBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.overlay.imageView = binding.screenshotImage
        binding.overlay.singlePoint = true
        binding.pointName.setText("summon")

        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSave.setOnClickListener { savePoint() }

        // Keep the status line in step with taps on the overlay.
        binding.overlay.onChanged = { updateStatus() }
    }

    private fun loadImage(uri: Uri) {
        val input = contentResolver.openInputStream(uri) ?: return
        val bitmap = BitmapFactory.decodeStream(input)
        input.close()

        currentBitmap = bitmap
        binding.screenshotImage.setImageBitmap(bitmap)
        binding.overlay.fullReset()
        updateStatus()
    }

    private fun updateStatus() {
        val box = binding.overlay.boxes.lastOrNull()
        binding.statusText.text =
            if (box == null) "No point marked yet" else "Point marked at ${box.x}, ${box.y}"
    }

    private fun savePoint() {
        val bmp = currentBitmap
        val name = binding.pointName.text.toString().trim()
        val box = binding.overlay.boxes.lastOrNull()

        if (bmp == null) {
            Toast.makeText(this, "Load a screenshot first", Toast.LENGTH_SHORT).show()
            return
        }
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name for this point", Toast.LENGTH_SHORT).show()
            return
        }
        if (box == null) {
            Toast.makeText(this, "Tap the button on the screenshot first", Toast.LENGTH_SHORT).show()
            return
        }

        ClickPointStorage.save(this, ClickPoint(name, bmp.width, bmp.height, box.x, box.y))
        Toast.makeText(this, "Saved '$name' at ${box.x}, ${box.y}", Toast.LENGTH_LONG).show()
    }
}

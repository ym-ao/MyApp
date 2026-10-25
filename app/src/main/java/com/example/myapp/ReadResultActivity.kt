package com.example.myapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivityReadResultBinding
import com.example.myapp.storage.LastReadStore

/** Shows the screenshot from the last "Read Grid" test with what OCR saw on top. */
class ReadResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val last = LastReadStore.load(this)
        if (last == null) {
            binding.summary.text = "No result yet. Run \"Read Grid Numbers\" first."
            return
        }

        binding.resultImage.setImageBitmap(last.screenshot)
        binding.resultOverlay.imageView = binding.resultImage
        binding.resultOverlay.readings = last.readings

        val total = last.readings.size
        val read = last.readings.count { it.value != null }
        val failed = last.readings.filter { it.value == null }.map { it.id }
        val failedText = if (failed.isEmpty()) {
            ""
        } else {
            "  Unreadable: " + failed.take(20).joinToString(", ") +
                if (failed.size > 20) " ..." else ""
        }
        binding.summary.text = "Grid '${last.profileName}': read $read of $total.$failedText"
    }
}

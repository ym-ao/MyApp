package com.example.myapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivityReadResultBinding
import com.example.myapp.model.ReadStatus
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

        val readings = last.readings
        val read = readings.count { it.status == ReadStatus.READ }
        val unsure = readings.filter { it.status == ReadStatus.LOW_CONFIDENCE }
        val bad = readings.filter { it.status == ReadStatus.UNREADABLE }
        val empty = readings.count { it.status == ReadStatus.EMPTY }
        val tiles = read + unsure.size + bad.size

        val sb = StringBuilder()
        sb.append("Grid '${last.profileName}': $tiles tiles - $read read, ")
        sb.append("${unsure.size} unsure (orange), ${bad.size} unreadable (red), $empty empty cells.")
        val problems = unsure + bad
        if (problems.isNotEmpty()) {
            sb.append("\nProblems: ")
            sb.append(problems.take(6).joinToString("   ") { "#${it.id} [${it.raw}]" })
            if (problems.size > 6) sb.append(" ...")
        }
        binding.summary.text = sb.toString()
    }
}

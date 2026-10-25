package com.example.myapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapp.databinding.ActivityMainBinding
import com.example.myapp.storage.GridStorage

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedProfile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSetupGrid.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnReadGrid.setOnClickListener { startReadTest() }
        binding.btnViewResult.setOnClickListener {
            startActivity(Intent(this, ReadResultActivity::class.java))
        }

        binding.profileSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    selectedProfile = parent?.getItemAtPosition(position) as? String
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshProfiles()
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

    private fun refreshProfiles() {
        val profiles = GridStorage.listProfiles(this)
        binding.profileSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles)
        val index = profiles.indexOf(selectedProfile)
        if (index >= 0) binding.profileSpinner.setSelection(index)
    }

    private fun startReadTest() {
        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(this, "Turn on the accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Reading the screen needs Android 11 or newer", Toast.LENGTH_LONG).show()
            return
        }
        val name = binding.profileSpinner.selectedItem as? String
        if (name == null) {
            Toast.makeText(this, "Set up a grid first", Toast.LENGTH_LONG).show()
            return
        }
        service.readGridAfterDelay(name, 5000L)
        moveTaskToBack(true)
    }
}

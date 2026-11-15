package com.example.myapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.myapp.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator

/** The app's home: a row of tabs, each one a part of the app. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val titles = listOf("Run", "Setup", "Loadout", "Clicks", "Library", "Code")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = titles.size

            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> RunFragment()
                1 -> SetupFragment()
                2 -> LoadoutFragment()
                3 -> ClicksFragment()
                4 -> LibraryFragment()
                else -> CodeFragment()
            }
        }
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }
}

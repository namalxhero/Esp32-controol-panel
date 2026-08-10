package com.nipuna.esp32controller

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int) = when (position) {
        0 -> OledFragment()
        else -> TerminalFragment()
    }
}

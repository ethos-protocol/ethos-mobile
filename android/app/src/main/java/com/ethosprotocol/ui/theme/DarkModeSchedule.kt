package com.ethosprotocol.ui.theme

import java.time.LocalTime

object DarkModeSchedule {
    fun shouldUseDarkMode(now: LocalTime = LocalTime.now()): Boolean {
        val evening = LocalTime.of(18, 0)
        val morning = LocalTime.of(6, 0)
        return now >= evening || now < morning
    }
}

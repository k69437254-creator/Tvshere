package com.hassouni.tv.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock

/** Real app-process CPU/RAM and battery temperature readings. */
class SystemMonitor(private val context: Context) {
    private var previousCpu = Process.getElapsedCpuTime()
    private var previousWall = SystemClock.elapsedRealtime()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun cpuPercent(): Int {
        val cpu = Process.getElapsedCpuTime()
        val wall = SystemClock.elapsedRealtime()
        val percent = ((cpu - previousCpu) * 100f /
            ((wall - previousWall).coerceAtLeast(1) * cores)).toInt()
        previousCpu = cpu
        previousWall = wall
        return percent.coerceIn(0, 100)
    }

    fun ramMb(): Int {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        return memory.totalPss / 1024
    }

    /** Returns -1 when Android does not expose battery temperature. */
    fun batteryTempC(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        return if (tenths == Int.MIN_VALUE || tenths <= 0) -1f else tenths / 10f
    }
}

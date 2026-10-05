package com.example.polarh10activityviewer

import android.app.Application
import com.example.polarh10activityviewer.ble.PolarBleManager
//PolarBleManager 很重要，它持有Polar SDK 实例，图表，sensors数据,这些东西不适合随着 SensorActivity 频繁销毁重建。
// Keep the paused session across Activity navigation while this process is alive.
class ActivityViewerApplication : Application() {
    val bleManager by lazy { PolarBleManager(this) }
}

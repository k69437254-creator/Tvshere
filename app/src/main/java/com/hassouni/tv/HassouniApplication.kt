package com.hassouni.tv

import android.app.Application
import com.serenegiant.utils.UVCUtils

/** Initialize UVCAndroid once before CameraHelper is constructed. */
class HassouniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UVCUtils.init(this)
    }
}

package com.nihyli.cloverpromotions

import android.app.Application
import com.nihyli.cloverpromotions.service.PromoMonitorService

class PromoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PromoMonitorService.start(this)
    }
}

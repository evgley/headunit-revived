package com.andrerinas.headunitrevived

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.LynkCoWiFi
import com.andrerinas.headunitrevived.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings)
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = LynkCoWiFi.getWifiManager(app) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)
}

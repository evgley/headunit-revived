package com.andrerinas.headunitrevived.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

object LynkCoWiFi {
    private const val TAG = "LynkCoWiFi"

    // Кеш для уже "подхаченного" WifiManager
    @Volatile
    private var cachedWifiManager: WifiManager? = null

    @SuppressLint("PrivateApi")
    fun getWifiManager(context: Context?): WifiManager? {
        // Если уже есть в кеше — возвращаем
        cachedWifiManager?.let {
            Log.d(TAG, "getWifiManager: returning cached WifiManager")
            return it
        }

        // Если кеша нет, но контекст null — ошибка
        if (context == null) {
            Log.e(TAG, "getWifiManager: context is null and no cached instance")
            return null
        }

        // Получаем новый WifiManager
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

        if (wifiManager == null) {
            Log.e(TAG, "getWifiManager: WIFI_SERVICE is not available")
            return null
        }

        Log.d(TAG, "getWifiManager: WifiManager obtained successfully, applying hack once")

        // Хак: устанавливаем поле mIsIpcpService в false через рефлексию (только один раз)
        try {
            val field = wifiManager.javaClass.getDeclaredField("mIsIpcpService")
            field.isAccessible = true
            field.setBoolean(wifiManager, false)
            Log.d(TAG, "Successfully set mIsIpcpService to false via reflection")
        } catch (e: NoSuchFieldException) {
            Log.d(TAG, "Field mIsIpcpService not found - skipping hack")
        } catch (e: IllegalAccessException) {
            Log.w(TAG, "Cannot access field mIsIpcpService: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while setting mIsIpcpService: ${e.message}")
        }

        // Сохраняем в кеш
        cachedWifiManager = wifiManager
        return wifiManager
    }

    fun enableWiFi(context: Context?): Boolean {
        val wifiManager = getWifiManager(context)
        if (wifiManager == null) {
            Log.e(TAG, "enableWiFi: unable to get WifiManager")
            return false
        }

        if (wifiManager.isWifiEnabled) {
            Log.d(TAG, "enableWiFi: Wi-Fi already enabled")
            return true
        }

        val result = wifiManager.setWifiEnabled(true)
        if (result) {
            Log.d(TAG, "enableWiFi: Wi-Fi enabled successfully")
        } else {
            Log.e(TAG, "enableWiFi: failed to enable Wi-Fi (check permission CHANGE_WIFI_STATE)")
        }
        return result
    }
}
package com.andrerinas.headunitrevived.view

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        val settings = App.provide(view.context).settings
        HeadUnitScreenConfig.init(view.context, view.resources.displayMetrics, settings)

        val usableW = HeadUnitScreenConfig.getUsableWidth()
        val usableH = HeadUnitScreenConfig.getUsableHeight()

        if (HeadUnitScreenConfig.forcedScale && view is ProjectionView) {
            val lp = view.layoutParams
            
            if (settings.stretchToFill) {
                // Mode A: Preserve aspect ratio using Adjusted dimensions
                val targetW = HeadUnitScreenConfig.getAdjustedWidth()
                val targetH = HeadUnitScreenConfig.getAdjustedHeight()

                lp.width = targetW
                lp.height = targetH

                // Center the view in the usable area
                if (lp is FrameLayout.LayoutParams) {
                    lp.gravity = Gravity.CENTER
                }
                view.layoutParams = lp

                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                AppLog.i("FORCED & STRETCH On: Resized view to ${targetW}x${targetH} (centered)")
            } else {
                // Mode B: Stretch to fill the usable area exactly (ignores aspect ratio)
                lp.width = usableW
                lp.height = usableH
                if (lp is FrameLayout.LayoutParams) {
                    lp.gravity = Gravity.TOP or Gravity.START
                }
                view.layoutParams = lp

                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                AppLog.i("FORCED & STRETCH Off: Resized view to match screen exactly: ${usableW}x${usableH}")
            }
        } else {
            // Modern way / TextureView: Use View scaling properties on a full-screen view
            val finalScaleX = HeadUnitScreenConfig.getScaleX()
            val finalScaleY = HeadUnitScreenConfig.getScaleY()

            if (view.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT || 
                view.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                val lp = view.layoutParams
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                if (lp is FrameLayout.LayoutParams) {
                    lp.gravity = Gravity.NO_GRAVITY
                }
                view.layoutParams = lp
            }

            // Normal centering for non-forced modes
            view.translationX = 0f
            view.translationY = 0f

            if (view is IProjectionView) {
                view.setVideoScale(finalScaleX, finalScaleY)
            } else {
                view.scaleX = finalScaleX
                view.scaleY = finalScaleY
            }
            AppLog.i("Normal Scale. scaleX: $finalScaleX, scaleY: $finalScaleY")
        }
    }
}

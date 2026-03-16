package com.andrerinas.headunitrevived.main.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.R
import com.google.android.material.slider.Slider

// Sealed class to represent different types of items in the settings list
sealed class SettingItem {
    abstract val stableId: String // Unique ID for DiffUtil

    data class SettingEntry(
        override val stableId: String, // Unique ID for the setting (e.g., "gpsNavigation")
        @StringRes val nameResId: Int,
        var value: String, // Current display value of the setting
        val onClick: (settingId: String) -> Unit // Callback when the setting is clicked
    ) : SettingItem()

    data class ToggleSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        @StringRes val descriptionResId: Int,
        var isChecked: Boolean,
        val isEnabled: Boolean = true,
        val onCheckedChanged: (Boolean) -> Unit
    ) : SettingItem()

    data class SliderSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        var value: String,
        var sliderValue: Float,
        val valueFrom: Float,
        val valueTo: Float,
        val stepSize: Float,
        val onValueChanged: (Float) -> Unit
    ) : SettingItem()

    data class CategoryHeader(override val stableId: String, @StringRes val titleResId: Int) : SettingItem()
}

class SettingsAdapter : ListAdapter<SettingItem, RecyclerView.ViewHolder>(SettingsDiffCallback()) { // Inherit from ListAdapter

    // Define View Types
    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SETTING = 1
        private const val VIEW_TYPE_TOGGLE = 3
        private const val VIEW_TYPE_SLIDER = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) { // Use getItem() from ListAdapter
            is SettingItem.CategoryHeader -> VIEW_TYPE_HEADER
            is SettingItem.SettingEntry -> VIEW_TYPE_SETTING
            is SettingItem.ToggleSettingEntry -> VIEW_TYPE_TOGGLE
            is SettingItem.SliderSettingEntry -> VIEW_TYPE_SLIDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.layout_category_header, parent, false))
            VIEW_TYPE_SETTING -> SettingViewHolder(inflater.inflate(R.layout.layout_setting_item, parent, false))
            VIEW_TYPE_TOGGLE -> ToggleSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_toggle, parent, false))
            VIEW_TYPE_SLIDER -> SliderSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_slider, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)

        if (holder is SettingViewHolder || holder is ToggleSettingViewHolder || holder is SliderSettingViewHolder) {
            updateItemVisuals(holder.itemView, position)
        }

        when (item) {
            is SettingItem.CategoryHeader -> (holder as HeaderViewHolder).bind(item)
            is SettingItem.SettingEntry -> (holder as SettingViewHolder).bind(item)
            is SettingItem.ToggleSettingEntry -> (holder as ToggleSettingViewHolder).bind(item)
            is SettingItem.SliderSettingEntry -> (holder as SliderSettingViewHolder).bind(item)
        }
    }

    private fun updateItemVisuals(view: View, position: Int) {
        val prev = if (position > 0) getItem(position - 1) else null
        val next = if (position < itemCount - 1) getItem(position + 1) else null

        val isTop = prev is SettingItem.CategoryHeader || prev == null
        val isBottom = next is SettingItem.CategoryHeader || next == null

        val bgRes = when {
            isTop && isBottom -> R.drawable.bg_setting_single
            isTop -> R.drawable.bg_setting_top
            isBottom -> R.drawable.bg_setting_bottom
            else -> R.drawable.bg_setting_middle
        }
        view.setBackgroundResource(bgRes)
    }

    // --- ViewHolder implementations ---

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.categoryTitle)
        fun bind(header: SettingItem.CategoryHeader) {
            title.setText(header.titleResId)
        }
    }

    class SettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingValue: TextView = itemView.findViewById(R.id.settingValue)
        
        fun bind(setting: SettingItem.SettingEntry) {
            settingName.setText(setting.nameResId)
            settingValue.text = setting.value
            itemView.setOnClickListener { setting.onClick(setting.stableId) }
        }
    }

    class ToggleSettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingDescription: TextView = itemView.findViewById(R.id.settingDescription)
        private val settingSwitch: Switch = itemView.findViewById(R.id.settingSwitch)

        fun bind(setting: SettingItem.ToggleSettingEntry) {
            settingName.setText(setting.nameResId)
            settingDescription.setText(setting.descriptionResId)
            settingSwitch.setOnCheckedChangeListener(null)
            settingSwitch.isChecked = setting.isChecked
            settingSwitch.isEnabled = setting.isEnabled
            itemView.alpha = if (setting.isEnabled) 1.0f else 0.5f
            itemView.isClickable = setting.isEnabled
            settingSwitch.setOnCheckedChangeListener { _, isChecked ->
                setting.onCheckedChanged(isChecked)
            }
            itemView.setOnClickListener {
                if (setting.isEnabled) settingSwitch.toggle()
            }
        }
    }

    class SliderSettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingValue: TextView = itemView.findViewById(R.id.settingValue)
        private val settingSlider: Slider = itemView.findViewById(R.id.settingSlider)

        fun bind(setting: SettingItem.SliderSettingEntry) {
            settingName.setText(setting.nameResId)
            settingValue.text = setting.value
            settingSlider.clearOnChangeListeners()
            settingSlider.valueFrom = setting.valueFrom
            settingSlider.valueTo = setting.valueTo
            settingSlider.stepSize = setting.stepSize
            settingSlider.value = setting.sliderValue
            settingSlider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    setting.onValueChanged(value)
                }
            }
        }
    }

    // DiffUtil.ItemCallback implementation
    private class SettingsDiffCallback : DiffUtil.ItemCallback<SettingItem>() {
        override fun areItemsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
            return oldItem.stableId == newItem.stableId
        }

        override fun areContentsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
            return when {
                oldItem is SettingItem.SettingEntry && newItem is SettingItem.SettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value
                oldItem is SettingItem.ToggleSettingEntry && newItem is SettingItem.ToggleSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.descriptionResId == newItem.descriptionResId && oldItem.isChecked == newItem.isChecked && oldItem.isEnabled == newItem.isEnabled
                oldItem is SettingItem.SliderSettingEntry && newItem is SettingItem.SliderSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value && oldItem.sliderValue == newItem.sliderValue
                oldItem is SettingItem.CategoryHeader && newItem is SettingItem.CategoryHeader ->
                    oldItem.titleResId == newItem.titleResId
                else -> false
            }
        }
    }
}

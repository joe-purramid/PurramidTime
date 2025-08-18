// PresetTimesDialog.kt
package com.example.purramid.purramidtime.timer.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.databinding.DialogPresetTimesBinding
import com.example.purramid.purramidtime.timer.PresetTime
import com.example.purramid.purramidtime.timer.PresetTimesManager
import com.example.purramid.purramidtime.util.dpToPx
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PresetTimesDialog : DialogFragment() {
    
    private var _binding: DialogPresetTimesBinding? = null
    private val binding get() = _binding!!
    
    @Inject lateinit var presetTimesManager: PresetTimesManager
    
    private var currentDurationMillis: Long = 0
    private var currentBackgroundColor: Int = Color.WHITE
    private var onPresetSelectedListener: ((Long) -> Unit)? = null
    private var onPresetSavedListener: (() -> Unit)? = null
    
    private val longPressedItems = mutableSetOf<String>()
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                // Position near the preset button
                setGravity(Gravity.BOTTOM or Gravity.END)
                attributes?.apply {
                    x = requireContext().dpToPx(16)
                    y = requireContext().dpToPx(100)
                }
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPresetTimesBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupUI()
        loadPresetTimes()
        
        // Handle outside clicks
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val rect = android.graphics.Rect()
                binding.contentContainer.getGlobalVisibleRect(rect)
                if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    dismiss()
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
    
    private fun setupUI() {
        // Setup grid layout
        binding.presetsGrid.columnCount = 4
        binding.presetsGrid.useDefaultMargins = true
    }
    
    private fun loadPresetTimes() {
        binding.presetsGrid.removeAllViews()
        longPressedItems.clear()
        
        val presetTimes = presetTimesManager.getPresetTimes()
        
        // Add saved preset times
        presetTimes.forEach { preset ->
            addPresetTimeView(preset)
        }
        
        // Add star button if not at max
        if (presetTimesManager.canAddMore()) {
            addStarButton()
        }
    }
    
    private fun addPresetTimeView(preset: PresetTime) {
        val itemView = LayoutInflater.from(context).inflate(
            R.layout.item_preset_time,
            binding.presetsGrid,
            false
        )
        
        val container = itemView.findViewById<View>(R.id.presetContainer)
        val timeText = itemView.findViewById<TextView>(R.id.presetTimeText)
        val deleteIcon = itemView.findViewById<ImageView>(R.id.deleteIcon)
        
        // Set time text
        timeText.text = formatTime(preset.durationMillis)
        
        // Set background color
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = requireContext().dpToPx(8).toFloat()
            setColor(preset.backgroundColor)
        }
        container.background = drawable
        
        // Set text color based on background luminance
        val textColor = if (ColorUtils.calculateLuminance(preset.backgroundColor) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        timeText.setTextColor(textColor)
        
        // Handle normal click
        container.setOnClickListener {
            if (!longPressedItems.contains(preset.id)) {
                onPresetSelectedListener?.invoke(preset.durationMillis)
                dismiss()
            }
        }
        
        // Handle long press
        container.setOnLongClickListener {
            if (longPressedItems.contains(preset.id)) {
                // Already in delete mode, remove it
                presetTimesManager.removePresetTime(preset.id)
                loadPresetTimes() // Refresh
            } else {
                // Enter delete mode
                longPressedItems.add(preset.id)
                showDeleteMode(container, deleteIcon)
            }
            true
        }
        
        // Handle delete icon click
        deleteIcon.setOnClickListener {
            presetTimesManager.removePresetTime(preset.id)
            loadPresetTimes() // Refresh
        }
        
        // Set layout params for grid
        val params = GridLayout.LayoutParams().apply {
            width = requireContext().dpToPx(60)
            height = requireContext().dpToPx(40)
            setMargins(
                requireContext().dpToPx(4),
                requireContext().dpToPx(4),
                requireContext().dpToPx(4),
                requireContext().dpToPx(4)
            )
        }
        
        binding.presetsGrid.addView(itemView, params)
    }
    
    private fun addStarButton() {
        val starView = ImageView(context).apply {
            setImageResource(R.drawable.ic_star)
            setColorFilter(ContextCompat.getColor(requireContext(), R.color.icon_inactive))
            scaleType = ImageView.ScaleType.CENTER
            
            val padding = requireContext().dpToPx(8)
            setPadding(padding, padding, padding, padding)
            
            // Set background
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = requireContext().dpToPx(8).toFloat()
                setStroke(requireContext().dpToPx(1), Color.GRAY)
                setColor(Color.TRANSPARENT)
            }
            background = drawable
            
            // Handle click
            setOnClickListener {
                // Animate fill to yellow
                setColorFilter(Color.YELLOW)
                postDelayed({
                    setColorFilter(ContextCompat.getColor(requireContext(), R.color.icon_inactive))
                }, 100)
                
                // Save current time
                if (currentDurationMillis > 0) {
                    if (presetTimesManager.addPresetTime(currentDurationMillis, currentBackgroundColor)) {
                        onPresetSavedListener?.invoke()
                        dismiss()
                    }
                }
            }
        }
        
        // Set layout params for grid
        val params = GridLayout.LayoutParams().apply {
            width = requireContext().dpToPx(60)
            height = requireContext().dpToPx(40)
            setMargins(
                requireContext().dpToPx(4),
                requireContext().dpToPx(4),
                requireContext().dpToPx(4),
                requireContext().dpToPx(4)
            )
        }
        
        binding.presetsGrid.addView(starView, params)
    }
    
    private fun showDeleteMode(container: View, deleteIcon: ImageView) {
        // Show star with yellow fill
        deleteIcon.visibility = View.VISIBLE
        deleteIcon.setImageResource(R.drawable.ic_star)
        deleteIcon.setColorFilter(Color.YELLOW)
        
        // Add visual feedback
        container.alpha = 0.7f
    }
    
    private fun formatTime(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        fun newInstance(
            currentDurationMillis: Long,
            currentBackgroundColor: Int,
            onPresetSelected: (Long) -> Unit,
            onPresetSaved: () -> Unit
        ): PresetTimesDialog {
            return PresetTimesDialog().apply {
                this.currentDurationMillis = currentDurationMillis
                this.currentBackgroundColor = currentBackgroundColor
                this.onPresetSelectedListener = onPresetSelected
                this.onPresetSavedListener = onPresetSaved
            }
        }
    }
}
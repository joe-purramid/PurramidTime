// StopwatchSettingsFragment.kt
package com.example.purramid.thepurramid.stopwatch.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.FragmentStopwatchSettingsBinding
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.stopwatch.StopwatchService
import com.example.purramid.thepurramid.stopwatch.viewmodel.StopwatchViewModel
import com.example.purramid.thepurramid.ui.PurramidPalette
import com.example.purramid.thepurramid.util.dpToPx
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StopwatchSettingsFragment : DialogFragment() {

    @Inject lateinit var instanceManager: InstanceManager

    private var _binding: FragmentStopwatchSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StopwatchViewModel by activityViewModels()

    private var blockListeners = false
    private var selectedStopwatchColor: Int = PurramidPalette.WHITE.colorInt
    private var selectedStopwatchColorView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStopwatchSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setTitle(R.string.stopwatch_settings_title)

        setupStopwatchColorPalette()
        setupListeners()
        observeViewModel()
    }

    private fun setupStopwatchColorPalette() {
        binding.stopwatchColorPalette.removeAllViews()
        val marginInPx = requireContext().dpToPx(8)

        PurramidPalette.appStandardPalette.forEach { namedColor ->
            val colorValue = namedColor.colorInt
            val colorView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    requireContext().dpToPx(40),
                    requireContext().dpToPx(40)
                ).apply {
                    setMargins(marginInPx, 0, marginInPx, 0)
                }

                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = requireContext().dpToPx(4).toFloat()
                    setColor(colorValue)
                    val strokeColor = if (Color.luminance(colorValue) > 0.5) Color.BLACK else Color.WHITE
                    setStroke(requireContext().dpToPx(1), strokeColor)
                }
                background = drawable
                tag = colorValue

                setOnClickListener {
                    selectedStopwatchColor = colorValue
                    updateStopwatchColorSelectionInUI(colorValue)
                    viewModel.updateOverlayColor(colorValue)
                }
            }
            binding.stopwatchColorPalette.addView(colorView)
        }
    }

    private fun updateStopwatchColorSelectionInUI(activeColor: Int) {
        for (i in 0 until binding.stopwatchColorPalette.childCount) {
            val childView = binding.stopwatchColorPalette.getChildAt(i)
            val viewColor = childView.tag as? Int ?: continue
            val drawable = childView.background as? GradientDrawable

            if (viewColor == activeColor) {
                drawable?.setStroke(requireContext().dpToPx(3), Color.CYAN)
                selectedStopwatchColorView = childView
            } else {
                val outline = if (Color.luminance(viewColor) > 0.5) Color.BLACK else Color.WHITE
                drawable?.setStroke(requireContext().dpToPx(1), outline)
            }
        }
    }

    private fun setupListeners() {
        binding.buttonCloseSettings.setOnClickListener {
            dismiss()
        }

        binding.layoutAddAnother.setOnClickListener {
            handleAddAnotherStopwatch()
        }

        binding.switchShowCentiseconds.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setShowCentiseconds(isChecked)
        }

        binding.switchLapTime.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setShowLapTimes(isChecked)
        }

        binding.switchSounds.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setSoundsEnabled(isChecked)
        }

        binding.switchNestStopwatch.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setNested(isChecked)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    Log.d(TAG, "Observed state: $state")
                    blockListeners = true

                    // Update Common Settings
                    binding.switchShowCentiseconds.isChecked = state.showCentiseconds
                    binding.switchLapTime.isChecked = state.showLapTimes
                    binding.switchSounds.isChecked = state.soundsEnabled
                    binding.switchNestStopwatch.isChecked = state.isNested

                    // Update color palette selection
                    selectedStopwatchColor = state.overlayColor
                    updateStopwatchColorSelectionInUI(selectedStopwatchColor)

                    // Update Add Another button state
                    val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMERS)
                    binding.layoutAddAnother.isEnabled = activeCount < 4
                    binding.iconAddAnother.alpha = if (activeCount < 4) 1.0f else 0.5f

                    blockListeners = false
                }
            }
        }
    }

    private fun handleAddAnotherStopwatch() {
        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMERS)
        if (activeCount >= 4) {
            Toast.makeText(
                requireContext(),
                getString(R.string.max_stopwatches_reached_snackbar),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Launch new stopwatch with current settings
        val intent = Intent(requireContext(), StopwatchService::class.java).apply {
            action = StopwatchService.ACTION_START_STOPWATCH
        }
        ContextCompat.startForegroundService(requireContext(), intent)

        // Dismiss settings
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StopwatchSettingsFragment"

        fun newInstance(stopwatchId: Int): StopwatchSettingsFragment {
            val fragment = StopwatchSettingsFragment()
            val args = Bundle()
            args.putInt(StopwatchViewModel.KEY_STOPWATCH_ID, stopwatchId)
            fragment.arguments = args
            return fragment
        }
    }
}
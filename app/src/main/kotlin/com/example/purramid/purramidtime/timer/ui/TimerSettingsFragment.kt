// TimerSettingsFragment.kt
package com.example.purramid.purramidtime.timer.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.databinding.FragmentTimerSettingsBinding
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.timer.ACTION_START_TIMER
import com.example.purramid.purramidtime.timer.EXTRA_DURATION_MS
import com.example.purramid.purramidtime.timer.TimerService
import com.example.purramid.purramidtime.timer.viewmodel.TimerViewModel
import com.example.purramid.purramidtime.ui.PurramidPalette
import com.example.purramid.purramidtime.util.dpToPx
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class TimerSettingsFragment : DialogFragment() {

    @Inject lateinit var instanceManager: InstanceManager

    private var _binding: FragmentTimersSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TimerViewModel by activityViewModels()

    private var blockListeners = false
    private var selectedTimerColor: Int = PurramidPalette.WHITE.colorInt
    private var selectedTimerColorView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimersSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setTitle(R.string.timer_settings_title)

        setupTimerColorPalette()
        setupListeners()
        observeViewModel()
    }

    private fun setupTimerColorPalette() {
        binding.timerColorPalette.removeAllViews()
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
                    selectedTimerColor = colorValue
                    updateTimerColorSelectionInUI(colorValue)
                    viewModel.updateOverlayColor(colorValue)
                }
            }
            binding.timerColorPalette.addView(colorView)
        }
    }

    private fun updateTimerColorSelectionInUI(activeColor: Int) {
        for (i in 0 until binding.timerColorPalette.childCount) {
            val childView = binding.timerColorPalette.getChildAt(i)
            val viewColor = childView.tag as? Int ?: continue
            val drawable = childView.background as? GradientDrawable

            if (viewColor == activeColor) {
                drawable?.setStroke(requireContext().dpToPx(3), Color.CYAN)
                selectedTimerColorView = childView
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
            handleAddAnotherTimer()
        }
        binding.switchPlaySoundOnEnd.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setPlaySoundOnEnd(isChecked)
        }
        binding.layoutSetSound.setOnClickListener {
            showSoundPicker()
        }
        binding.switchNestTimer.setOnCheckedChangeListener { _, isChecked ->
            if (blockListeners) return@setOnCheckedChangeListener
            viewModel.setNested(isChecked)
        }
        binding.layoutSetCountdown.setOnClickListener {
            showSetCountdownDialog()
        }
        binding.buttonPresetTimes.setOnClickListener {
            showPresetTimesDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    Log.d(TAG, "Observed state: $state")
                    blockListeners = true

                    // Update duration display
                    val durationStr = formatDuration(state.initialDurationMillis)
                    binding.textViewCurrentDuration.text = durationStr

                    // Update simple switches (if keeping them)
                    binding.switchPlaySoundOnEnd.isChecked = state.playSoundOnEnd
                    binding.switchNestTimer.isChecked = state.isNested

                    // Update color palette selection
                    selectedTimerColor = state.overlayColor
                    updateTimerColorSelectionInUI(selectedTimerColor)

                    // Update Add Another button state
                    val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMER)
                    binding.layoutAddAnother.isEnabled = activeCount < 4
                    binding.iconAddAnother.alpha = if (activeCount < 4) 1.0f else 0.5f

                    // Update preset button visibility based on timer type
                    binding.buttonPresetTimes.visibility = if (state.type == TimerType.COUNTDOWN) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                    blockListeners = false
                }
            }
        }
    }
    private fun populateDurationFields(totalMillis: Long) {
        val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60

        if (binding.editTextHours.text.toString() != hours.toString()) {
            binding.editTextHours.setText(hours.toString())
        }
        if (binding.editTextMinutes.text.toString() != minutes.toString()) {
            binding.editTextMinutes.setText(minutes.toString())
        }
        if (binding.editTextSeconds.text.toString() != seconds.toString()) {
            binding.editTextSeconds.setText(seconds.toString())
        }
    }

    private fun saveDurationFromInput() {
        val hours = binding.editTextHours.text.toString().toLongOrNull() ?: 0L
        val minutes = binding.editTextMinutes.text.toString().toLongOrNull() ?: 0L
        val seconds = binding.editTextSeconds.text.toString().toLongOrNull() ?: 0L

        if (hours < 0 || minutes < 0 || seconds < 0 || minutes >= 60 || seconds >= 60) {
            Log.w(TAG, "Invalid duration input.")
            Toast.makeText(requireContext(), getString(R.string.invalid_duration), Toast.LENGTH_SHORT).show()
            populateDurationFields(viewModel.uiState.value.initialDurationMillis)
            return
        }

        // Check max time limit (99:59:59)
        if (hours > 99) {
            Toast.makeText(requireContext(), getString(R.string.max_duration_exceeded), Toast.LENGTH_SHORT).show()
            binding.editTextHours.setText("99")
            return
        }

        val totalMillis = TimeUnit.HOURS.toMillis(hours) +
                TimeUnit.MINUTES.toMillis(minutes) +
                TimeUnit.SECONDS.toMillis(seconds)
        viewModel.setInitialDuration(totalMillis)
        Log.d(TAG, "Saved duration: $totalMillis ms")
    }

    private fun handleAddAnotherTimer() {
        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMER)
        if (activeCount >= 4) {
            Toast.makeText(
                requireContext(),
                getString(R.string.max_timers_reached_snackbar),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Launch new timer with current settings
        val currentState = viewModel.uiState.value
        val intent = Intent(requireContext(), TimerService::class.java).apply {
            action = ACTION_START_TIMER  // Changed from conditional action
            putExtra(EXTRA_DURATION_MS, currentState.initialDurationMillis)
        }
        ContextCompat.startForegroundService(requireContext(), intent)

        dismiss()
    }

    private fun showSoundPicker() {
        val currentSound = viewModel.uiState.value.selectedSoundUri
        SoundPickerDialog.newInstance(currentSound) { selectedUri ->
            if (selectedUri == "MUSIC_URL_OPTION") {
                // Show music URL dialog
                showMusicUrlDialog()
            } else {
                viewModel.setSelectedSound(selectedUri)
            }
        }.show(childFragmentManager, "SoundPickerDialog")
    }

    private fun showMusicUrlDialog() {
        val currentUrl = viewModel.uiState.value.musicUrl
        val recentUrls = viewModel.uiState.value.recentMusicUrls

        MusicUrlDialog.newInstance(currentUrl, recentUrls) { url ->
            viewModel.setMusicUrl(url)
            // Clear selected sound URI when using music URL
            viewModel.setSelectedSound(null)
        }.show(childFragmentManager, "MusicUrlDialog")
    }

    private fun showSetCountdownDialog() {
        val currentDuration = viewModel.uiState.value.initialDurationMillis
        SetCountdownDialog.newInstance(currentDuration) { newDuration ->
            viewModel.setInitialDuration(newDuration)
        }.show(childFragmentManager, "SetCountdownDialog")
    }

    private fun showPresetTimesDialog() {
        val currentState = viewModel.uiState.value
        PresetTimesDialog.newInstance(
            currentDurationMillis = currentState.initialDurationMillis,
            currentBackgroundColor = currentState.overlayColor,
            onPresetSelected = { durationMillis ->
                viewModel.loadPresetFromManager(durationMillis)
            },
            onPresetSaved = {
                viewModel.refreshPresetTimes()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_saved),
                    Toast.LENGTH_SHORT
                ).show()
            }
        ).show(childFragmentManager, "PresetTimesDialog")
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TimerSettingsFragment"

        fun newInstance(timerId: Int): TimerSettingsFragment {
            val fragment = TimerSettingsFragment()
            val args = Bundle()
            args.putInt(TimerViewModel.KEY_TIMER_ID, timerId)
            fragment.arguments = args
            return fragment
        }
    }
}
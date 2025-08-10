// MainActivity.kt
package com.example.purramid.purramidtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.window.layout.WindowMetricsCalculator
import com.example.purramid.purramidtime.clock.ClockActivity
import com.example.purramid.purramidtime.databinding.ActivityMainBinding
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.stopwatch.StopwatchActivity
import com.example.purramid.purramidtime.timer.TimerActivity
import com.example.purramid.purramidtime.AboutActivity
import com.example.purramid.purramidtime.util.dpToPx
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject

// --- Define simple Enums for Size Classes (for XML Views context) ---
// Based on Material Design breakpoints: https://m3.material.io/foundations/layout/applying-layout/window-size-classes
enum class WindowWidthSizeClass { COMPACT, MEDIUM, EXPANDED }
enum class WindowHeightSizeClass { COMPACT, MEDIUM, EXPANDED }

data class AppIntent(
    val title: String,
    @get:androidx.annotation.DrawableRes val iconResId: Int,
    val action: (Context) -> Unit,
    val id: String
)

class IntentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val iconImageView: ImageView = itemView.findViewById(R.id.intentIconImageView)
    val titleTextView: TextView = itemView.findViewById(R.id.intentTitleTextView)
}

class IntentAdapter(private val intents: List<AppIntent>, private val onItemClick: (AppIntent) -> Unit) :
    RecyclerView.Adapter<IntentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntentViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_intent, parent, false)
        return IntentViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: IntentViewHolder, position: Int) {
        val currentIntent = intents[position]
        holder.iconImageView.setImageResource(currentIntent.iconResId)
        holder.titleTextView.text = currentIntent.title
        holder.itemView.setOnClickListener {
            onItemClick(currentIntent)
        }
    }

    override fun getItemCount() = intents.size
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: IntentAdapter
    private var isIntentsVisible = false
    private val allIntents = mutableListOf<AppIntent>()
    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0

    @Inject
    lateinit var instanceManager: InstanceManager

    companion object {
        private const val TAG = "MainActivity"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()

        // Calculate screen dimensions in pixels
        val displayMetrics = resources.displayMetrics
        screenWidthPx = displayMetrics.widthPixels
        screenHeightPx = displayMetrics.heightPixels

        // --- Calculate Window Size Classes ---
        val wmc = WindowMetricsCalculator.getOrCreate()
        val currentWindowMetrics = wmc.computeCurrentWindowMetrics(this)
        val widthDp = currentWindowMetrics.bounds.width() / displayMetrics.density
        val heightDp = currentWindowMetrics.bounds.height() / displayMetrics.density

        val widthSizeClass = when {
            widthDp < 600f -> WindowWidthSizeClass.COMPACT
            widthDp < 840f -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }

        val heightSizeClass = when {
            heightDp < 480f -> WindowHeightSizeClass.COMPACT
            heightDp < 900f -> WindowHeightSizeClass.MEDIUM
            else -> WindowHeightSizeClass.EXPANDED
        }

        // Load app icon - using the proper launcher image
        binding.appIconImageView.setImageDrawable(ContextCompat.getDrawable(this, R.mipmap.purramidtime_launcher))

        defineAppIntents()

        // Set up the RecyclerView with curved layout
        binding.intentsRecyclerView.layoutManager = CurvedLinearLayoutManager(this)
        adapter = IntentAdapter(allIntents) { appIntent ->
            animateIntentSelection(appIntent) {
                appIntent.action.invoke(this@MainActivity)
                if (isIntentsVisible) {
                    hideIntentsAnimated()
                }
            }
        }
        binding.intentsRecyclerView.adapter = adapter

        // Set up click listener for the app icon button
        binding.appIconButtonContainer.setOnClickListener {
            toggleIntentsVisibility()
        }

        // Set up touch listener for handling clicks outside the intents
        binding.root.setOnTouchListener { _, event ->
            if (isIntentsVisible && event.action == MotionEvent.ACTION_DOWN) {
                if (!isTouchInsideView(event.rawX, event.rawY, binding.appIconButtonContainer) &&
                    !isTouchInsideView(event.rawX, event.rawY, binding.intentsRecyclerView)
                ) {
                    hideIntentsAnimated()
                    return@setOnTouchListener true
                }
            }
            return@setOnTouchListener false
        }

        // Apply Layout Adaptations based on Size Classes
        adaptLayoutToSizeClasses(widthSizeClass, heightSizeClass)
        // Set initial freeform window size
        setInitialFreeformWindowSize()
    }

    private fun setupRecyclerView() {
        // This is now handled in defineAppIntents()
    }

    // Define Intents according to specification order
    private fun defineAppIntents() {
        allIntents.clear()
        allIntents.addAll(
            listOf(
                AppIntent(
                    title = getString(R.string.clock_title),
                    iconResId = R.mipmap.tp_clock_launcher,
                    id = "clock",
                    action = { context ->
                        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.CLOCK)
                        if (activeCount > 0) {
                            val intent = Intent(context, ClockActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            context.startActivity(intent)
                        } else {
                            val intent = Intent(context, ClockActivity::class.java)
                            context.startActivity(intent)
                        }
                    }
                ),
                AppIntent(
                    title = getString(R.string.timers_title),
                    iconResId = R.mipmap.tp_timer_launcher,
                    id = "timers",
                    action = { context ->
                        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMER)
                        if (activeCount > 0) {
                            val intent = Intent(context, TimerActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            context.startActivity(intent)
                        } else {
                            val intent = Intent(context, TimerActivity::class.java)
                            context.startActivity(intent)
                        }
                    }
                ),
                AppIntent(
                    title = getString(R.string.stopwatch_title),
                    iconResId = R.mipmap.tp_stopwatch_launcher,
                    id = "stopwatch",
                    action = { context ->
                        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.STOPWATCH)
                        if (activeCount > 0) {
                            val intent = Intent(context, StopwatchActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            context.startActivity(intent)
                        } else {
                            val intent = Intent(context, StopwatchActivity::class.java)
                            context.startActivity(intent)
                        }
                    }
                ),
                AppIntent(
                    title = getString(R.string.about),
                    iconResId = R.mipmap.tp_about_launcher,
                    id = "about",
                    action = { context ->
                        val intent = Intent(context, AboutActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        context.startActivity(intent)
                    }
                )
            )
        )
    }

    // Helper Function for Touch Handling
    private fun isTouchInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        if (!view.isShown) {
            return false
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewRect = Rect(
            location[0], location[1],
            location[0] + view.width, location[1] + view.height
        )
        return viewRect.contains(rawX.toInt(), rawY.toInt())
    }

    // Intent Selection Animation
    private fun animateIntentSelection(appIntent: AppIntent, onEndAction: () -> Unit) {
        val animatorSet = AnimatorSet().apply {
            play(ObjectAnimator.ofFloat(binding.intentsRecyclerView, "alpha", 1f, 0.8f, 1f))
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEndAction()
                }
            })
            start()
        }
    }

    // Visibility Toggle Function
    private fun toggleIntentsVisibility() {
        if (isIntentsVisible) {
            hideIntentsAnimated()
        } else {
            showIntentsAnimated()
        }
    }

    private fun showIntentsAnimated() {
        if (isIntentsVisible) return

        binding.intentsRecyclerView.visibility = View.VISIBLE
        binding.intentsRecyclerView.alpha = 0f

        val scaleDownX = ObjectAnimator.ofFloat(binding.appIconImageView, View.SCALE_X, 1f, 0.8f)
        val scaleDownY = ObjectAnimator.ofFloat(binding.appIconImageView, View.SCALE_Y, 1f, 0.8f)
        val fadeIn = ObjectAnimator.ofFloat(binding.intentsRecyclerView, View.ALPHA, 0f, 1f)

        AnimatorSet().apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
            playTogether(scaleDownX, scaleDownY, fadeIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    isIntentsVisible = true
                }
            })
            start()
        }
    }

    private fun hideIntentsAnimated() {
        if (!isIntentsVisible) return

        val slideUpDistancePx = this.dpToPx(-30)

        val scaleUpX = ObjectAnimator.ofFloat(binding.appIconImageView, View.SCALE_X, binding.appIconImageView.scaleX, 1f)
        val scaleUpY = ObjectAnimator.ofFloat(binding.appIconImageView, View.SCALE_Y, binding.appIconImageView.scaleY, 1f)
        val fadeOut = ObjectAnimator.ofFloat(binding.intentsRecyclerView, View.ALPHA, 1f, 0f)
        val slideUp = ObjectAnimator.ofFloat(binding.intentsRecyclerView, View.TRANSLATION_Y, 0f, slideUpDistancePx.toFloat())

        AnimatorSet().apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
            playTogether(scaleUpX, scaleUpY, fadeOut, slideUp)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.intentsRecyclerView.visibility = View.GONE
                    binding.intentsRecyclerView.translationY = 0f
                    isIntentsVisible = false
                }
            })
            start()
        }
    }

    // Utility and Other Functions
    private fun adaptLayoutToSizeClasses(widthSizeClass: WindowWidthSizeClass, heightSizeClass: WindowHeightSizeClass) {
        val layoutManager = binding.intentsRecyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.orientation =
            if (widthSizeClass == WindowWidthSizeClass.EXPANDED || widthSizeClass == WindowWidthSizeClass.MEDIUM) {
                RecyclerView.HORIZONTAL
            } else {
                RecyclerView.VERTICAL
            }

        val paddingSize = if (widthSizeClass == WindowWidthSizeClass.EXPANDED) {
            resources.getDimensionPixelSize(R.dimen.large_screen_padding)
        } else {
            resources.getDimensionPixelSize(R.dimen.default_padding)
        }
        binding.intentsRecyclerView.setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
    }

    private fun setInitialFreeformWindowSize(widthFraction: Float = 0.6f, heightFraction: Float = 0.7f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val window = this.window
                val layoutParams = WindowManager.LayoutParams()
                layoutParams.copyFrom(window.attributes)
                val calculatedWidthFraction = if (screenWidthPx > 3000) 0.25f else 0.4f
                val calculatedHeightFraction = if (screenWidthPx > 3000) 0.3f else 0.45f
                layoutParams.width = (screenWidthPx * calculatedWidthFraction).toInt()
                layoutParams.height = (screenHeightPx * calculatedHeightFraction).toInt()
                window.attributes = layoutParams
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

// Curved LinearLayoutManager for the app-intent list
class CurvedLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        super.onLayoutChildren(recycler, state)

        // Apply curved positioning to children
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            val center = width / 2f
            val childCenter = (child.left + child.right) / 2f
            val distanceFromCenter = (childCenter - center) / center

            // Apply subtle curve effect
            val curveOffset = distanceFromCenter * distanceFromCenter * 20f
            child.translationY = curveOffset
        }
    }
}
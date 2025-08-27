// MainActivity.kt
package com.example.purramid.purramidtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.example.purramid.purramidtime.util.dpToPx
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// --- Define simple Enums for Size Classes (for XML Views context) ---
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

        // --- Calculate Window Size Classes ---
        val wmc = WindowMetricsCalculator.getOrCreate()
        val currentWindowMetrics = wmc.computeCurrentWindowMetrics(this)
        val displayMetrics = resources.displayMetrics
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
                finish() // Close MainActivity after launching app-intent
            }
        }
        binding.intentsRecyclerView.adapter = adapter

        // Auto-show the intents menu immediately
        binding.appIconImageView.post {
            showIntentsAnimated()
        }

        // Set up touch listener for handling clicks outside the intents
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!isTouchInsideView(event.rawX, event.rawY, binding.intentsRecyclerView)) {
                    finish() // Close the activity if clicked outside
                    return@setOnTouchListener true
                }
            }
            return@setOnTouchListener false
        }

        // Apply Layout Adaptations based on Size Classes
        adaptLayoutToSizeClasses(widthSizeClass, heightSizeClass)
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
                        val intent = Intent(context, ClockActivity::class.java)
                        context.startActivity(intent)
                    }
                ),
                AppIntent(
                    title = getString(R.string.timer_title),
                    iconResId = R.mipmap.tp_timer_launcher,
                    id = "timer",
                    action = { context ->
                        val intent = Intent(context, TimerActivity::class.java)
                        context.startActivity(intent)
                    }
                ),
                AppIntent(
                    title = getString(R.string.stopwatch_title),
                    iconResId = R.mipmap.tp_stopwatch_launcher,
                    id = "stopwatch",
                    action = { context ->
                        val intent = Intent(context, StopwatchActivity::class.java)
                        context.startActivity(intent)
                    }
                ),
                AppIntent(
                    title = getString(R.string.about),
                    iconResId = R.drawable.ic_info,
                    id = "about",
                    action = { context ->
                        val intent = Intent(context, AboutActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            )
        )
        Log.d(TAG, "Total intents defined: ${allIntents.size}")
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

    private fun showIntentsAnimated() {
        if (isIntentsVisible) return

        binding.intentsRecyclerView.visibility = View.VISIBLE
        binding.intentsRecyclerView.alpha = 0f

        val fadeIn = ObjectAnimator.ofFloat(binding.intentsRecyclerView, View.ALPHA, 0f, 1f)

        fadeIn.apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    isIntentsVisible = true
                }
            })
            start()
        }
    }

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
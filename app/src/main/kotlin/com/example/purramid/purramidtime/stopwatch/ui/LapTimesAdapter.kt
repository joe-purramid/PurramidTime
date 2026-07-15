// LapTimesAdapter.kt
package com.example.purramid.purramidtime.stopwatch.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.purramidtime.R

/**
 * Renders the stopwatch lap list. Time formatting stays in the service (which owns
 * the Hundredths setting), so items arrive pre-formatted; this adapter only lays
 * them out and applies the current text color for overlay theming.
 *
 * Laps are supplied oldest-first (spec 10.1.3.1.1 / 10.1.3.1.2).
 */
class LapTimesAdapter : RecyclerView.Adapter<LapTimesAdapter.LapViewHolder>() {

    /** @param label the visible "N.  MM:SS.cc" text; @param description the screen-reader phrase. */
    data class LapItem(val label: String, val description: String)

    private var items: List<LapItem> = emptyList()
    private var textColor: Int = Color.BLACK

    fun submit(newItems: List<LapItem>, color: Int) {
        items = newItems
        textColor = color
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LapViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lap_time, parent, false) as TextView
        return LapViewHolder(view)
    }

    override fun onBindViewHolder(holder: LapViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = item.label
        holder.textView.setTextColor(textColor)
        holder.textView.contentDescription = item.description
    }

    override fun getItemCount(): Int = items.size

    class LapViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}

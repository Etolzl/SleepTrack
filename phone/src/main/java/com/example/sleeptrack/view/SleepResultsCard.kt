package com.example.sleeptrack.view

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.sleeptrack.R
import java.text.SimpleDateFormat
import java.util.*

class SleepResultsCard private constructor(view: View) {
    private val title: TextView = view.findViewById(R.id.title)
    private val duration: TextView = view.findViewById(R.id.duration)
    private val heartRate: TextView = view.findViewById(R.id.heart_rate)
    private val movement: TextView = view.findViewById(R.id.movement)
    private val sleepQuality: TextView = view.findViewById(R.id.sleep_quality)
    private val sessionId: TextView = view.findViewById(R.id.session_id)

    companion object {
        fun create(parent: ViewGroup): SleepResultsCard {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.sleep_results_card, parent, false)
            return SleepResultsCard(view)
        }
    }

    fun bind(data: Map<String, Any>, sessionId: String) {
        try {
            val startTime = when (val value = data["startTime"]) {
                is Double -> value.toLong()
                is Long -> value
                else -> System.currentTimeMillis()
            }

            val durationSeconds = when (val value = data["duration"]) {
                is Double -> value.toLong()
                is Long -> value
                else -> 0L
            }

            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            val dateStr = dateFormat.format(Date(startTime))

            title.text = "Sleep Session - $dateStr"
            duration.text = "Duration: ${formatDuration(durationSeconds)}"
            heartRate.text = "Avg Heart Rate: ${data["averageHeartRate"]} bpm"
            movement.text = "Movement Score: ${"%.2f".format(data["movementScore"])}"
            sleepQuality.text = "Sleep Quality: ${data["sleepQuality"]}"
            this.sessionId.text = "Session ID: ${sessionId.take(8)}..."
        } catch (e: Exception) {
            title.text = "Error displaying data"
            Log.e("SleepResultsCard", "Error binding data", e)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%dh %02dm %02ds", hours, minutes, secs)
    }

    val view: View get() = title.rootView
}
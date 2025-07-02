package com.example.sleeptrack.presentation

import java.io.Serializable

data class SleepSession(
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long = 0L,
    val heartRates: MutableList<Int> = mutableListOf(),
    val movements: MutableList<Float> = mutableListOf()
) : Serializable {
    fun calculateAverageHeartRate(): Int {
        return if (heartRates.isEmpty()) 0 else heartRates.average().toInt()
    }

    fun calculateMovementScore(): Float {
        return if (movements.isEmpty()) 0f else movements.average().toFloat()
    }

    fun calculateSleepQuality(): String {
        val avgHeartRate = calculateAverageHeartRate()
        val movementScore = calculateMovementScore()

        return when {
            avgHeartRate < 60 && movementScore < 0.5 -> "Profundo"
            avgHeartRate < 70 && movementScore < 1.0 -> "Moderado"
            else -> "Ligero"
        }
    }

    companion object {
        private const val serialVersionUID = 1L  // Para control de versiones
    }
}
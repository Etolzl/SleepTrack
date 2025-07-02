package com.example.sleeptrack

import android.util.Log
import com.google.firebase.database.GenericTypeIndicator
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*

class FirebaseManager {
    private val database = Firebase.database

    fun uploadSleepData(userId: String? = null, sessionId: String, data: Map<String, Any>) {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val currentDate = dateFormat.format(Date())

            val baseRef = if (!userId.isNullOrEmpty()) {
                database.getReference("users").child(userId).child("sleepSessions")
            } else {
                database.getReference("sleepSessions")
            }

            val sessionRef = baseRef.child(currentDate).child(sessionId)
            sessionRef.setValue(data)
                .addOnSuccessListener {
                    Log.d("FirebaseManager", "Datos de sueño subidos correctamente")
                }
                .addOnFailureListener { e ->
                    Log.e("FirebaseManager", "Error al subir datos", e)
                }
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Excepción al subir datos", e)
        }
    }

    fun getSleepData(
        onDataLoaded: (String, Map<String, Any>) -> Unit,
        onComplete: () -> Unit = {}
    ) {
        try {
            database.getReference("sleepSessions")
                .get()
                .addOnSuccessListener { snapshot ->
                    if (!snapshot.exists()) {
                        Log.d("FirebaseManager", "No se encontraron datos históricos")
                        onComplete()
                        return@addOnSuccessListener
                    }

                    var loadedCount = 0
                    val totalItems = snapshot.children.count()

                    snapshot.children.forEach { dateNode ->
                        dateNode.children.forEach { sessionNode ->
                            try {
                                val sessionId = sessionNode.key ?: ""
                                val data = sessionNode.getValue(object :
                                    GenericTypeIndicator<Map<String, Any>>() {})

                                val summary = data?.get("summary") as? Map<String, Any>
                                summary?.let {
                                    onDataLoaded(sessionId, it)
                                    loadedCount++
                                    if (loadedCount == totalItems) {
                                        onComplete()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("FirebaseManager", "Error al analizar datos", e)
                            }
                        }
                    }

                    if (totalItems == 0) {
                        onComplete()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FirebaseManager", "Error al cargar datos", e)
                    onComplete()
                }
        } catch (e: Exception) {
            Log.e("FirebaseManager", "Excepción al cargar datos", e)
            onComplete()
        }
    }
}
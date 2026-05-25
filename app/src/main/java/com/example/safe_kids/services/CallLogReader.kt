package com.example.safe_kids.services

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

class CallLogReader(private val context: Context) {
    private val TAG = "CallLogReader"
    
    suspend fun syncCallLogs(childId: String) {
        try {
            val calls = readLast50Calls()
            if (calls.isNotEmpty()) {
                val db = FirebaseFirestore.getInstance()
                val batch = db.batch()
                
                calls.forEach { call ->
                    val callRef = db.collection("children")
                        .document(childId)
                        .collection("callLog")
                        .document(call["timestamp"] as String)
                    
                    batch.set(callRef, call)
                }
                
                batch.commit().await()
                Log.d(TAG, "Synced ${calls.size} call logs to Firestore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync call logs", e)
        }
    }
    
    private fun readLast50Calls(): List<Map<String, Any>> {
        val calls = mutableListOf<Map<String, Any>>()
        val resolver: ContentResolver = context.contentResolver
        val uri: Uri = CallLog.Calls.CONTENT_URI
        
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        
        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT 50"
        
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, projection, null, null, sortOrder)
            
            cursor?.use { c ->
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                
                while (c.moveToNext()) {
                    val number = c.getString(numberIdx) ?: "Unknown"
                    val duration = c.getLong(durationIdx)
                    val type = c.getInt(typeIdx)
                    val timestamp = c.getLong(dateIdx)
                    
                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                        else -> "UNKNOWN"
                    }
                    
                    calls.add(mapOf(
                        "number" to number,
                        "duration" to duration,
                        "type" to callType,
                        "timestamp" to timestamp.toString(),
                        "date" to Date(timestamp).toString()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call log", e)
        } finally {
            cursor?.close()
        }
        
        return calls
    }
}

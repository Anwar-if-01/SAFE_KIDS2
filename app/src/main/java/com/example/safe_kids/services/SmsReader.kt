package com.example.safe_kids.services

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

class SmsReader(private val context: Context) {
    private val TAG = "SmsReader"
    
    suspend fun syncSmsLogs(childId: String) {
        try {
            val smsList = readLast50Sms()
            if (smsList.isNotEmpty()) {
                val db = FirebaseFirestore.getInstance()
                val batch = db.batch()
                
                smsList.forEach { sms ->
                    val smsRef = db.collection("children")
                        .document(childId)
                        .collection("smsLog")
                        .document(sms["timestamp"] as String)
                    
                    batch.set(smsRef, sms)
                }
                
                batch.commit().await()
                Log.d(TAG, "Synced ${smsList.size} SMS logs to Firestore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync SMS logs", e)
        }
    }
    
    private fun readLast50Sms(): List<Map<String, Any>> {
        val smsList = mutableListOf<Map<String, Any>>()
        val resolver: ContentResolver = context.contentResolver
        
        // Read inbox SMS
        readSmsFromUri(resolver, Telephony.Sms.Inbox.CONTENT_URI, "INBOX", smsList)
        // Read sent SMS
        readSmsFromUri(resolver, Telephony.Sms.Sent.CONTENT_URI, "SENT", smsList)
        
        // Sort by date and take the last 50
        return smsList.sortedByDescending { it["date"] as Long }.take(50)
    }
    
    private fun readSmsFromUri(resolver: ContentResolver, uri: Uri, type: String, smsList: MutableList<Map<String, Any>>) {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE // For INBOX/SENT differentiation
        )
        
        // No LIMIT in query, we'll sort and limit after combining INBOX and SENT
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, projection, null, null, null)
            
            cursor?.use { c ->
                val addressIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val smsTypeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                
                while (c.moveToNext()) {
                    val address = c.getString(addressIdx) ?: "Unknown"
                    val body = c.getString(bodyIdx) ?: ""
                    val date = c.getLong(dateIdx)
                    val smsType = c.getInt(smsTypeIdx)
                    
                    smsList.add(mapOf(
                        "address" to address,
                        "body" to body,
                        "date" to date,
                        "type" to type, // Override Telephony.Sms.TYPE with our custom INBOX/SENT
                        "timestamp" to date.toString() // Use date as unique ID for Firestore
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS from $uri", e)
        } finally {
            cursor?.close()
        }
    }
}

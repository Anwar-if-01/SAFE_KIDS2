package com.example.safe_kids.auth

import android.util.Log
import com.example.safe_kids.data.ChildCommand
import com.example.safe_kids.data.CommandStatus
import com.example.safe_kids.data.CommandType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class ParentActionsManager(private val firestore: FirebaseFirestore) {
    
    private val TAG = "ParentActionsManager"
    private val commandsCollection = AuthConfig.CHILD_COMMANDS_COLLECTION

    suspend fun sendCommand(childId: String, parentId: String, type: CommandType, payload: Map<String, String> = emptyMap()): Result<String> {
        return try {
            val command = ChildCommand(
                childId = childId,
                parentId = parentId,
                type = type,
                payload = payload,
                status = CommandStatus.PENDING
            )
            
            firestore.collection(commandsCollection)
                .document(command.id)
                .set(command)
                .await()
            
            Log.d(TAG, "Sent command $type to child $childId")
            Result.success(command.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command $type", e)
            Result.failure(e)
        }
    }

    suspend fun sendInstantBlock(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.INSTANT_BLOCK)

    suspend fun sendUnblock(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.UNBLOCK)

    suspend fun requestScreenshot(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.TAKE_SCREENSHOT)

    suspend fun startAudioMonitor(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.START_AUDIO_MONITOR)

    suspend fun stopAudioMonitor(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.STOP_AUDIO_MONITOR)

    suspend fun setStudyMode(childId: String, parentId: String, active: Boolean) = 
        sendCommand(childId, parentId, if (active) CommandType.ACTIVATE_STUDY_MODE else CommandType.DEACTIVATE_STUDY_MODE)

    suspend fun setSleepMode(childId: String, parentId: String, active: Boolean) = 
        sendCommand(childId, parentId, if (active) CommandType.ACTIVATE_SLEEP_MODE else CommandType.DEACTIVATE_SLEEP_MODE)

    suspend fun lockDevice(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.LOCK_DEVICE)

    suspend fun unlockDevice(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.UNLOCK_DEVICE)

    suspend fun requestCurrentLocation(childId: String, parentId: String) = 
        sendCommand(childId, parentId, CommandType.REQUEST_LOCATION)
}

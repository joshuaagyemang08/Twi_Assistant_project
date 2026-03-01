package com.example.twiassistant.english

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.twiassistant.device_control.DeviceActions

/**
 * Dedicated handler for English messaging - completely separate from Twi flow
 * Handles SMS and WhatsApp messages directly without translation or complex state management
 */
class EnglishMessageHandler(private val context: Context) {
    
    private val deviceActions = DeviceActions(context)
    
    /**
     * Send SMS message in English directly - opens SMS app with pre-filled message
     */
    fun sendSmsMessage(contactNumber: String, message: String): Boolean {
        Log.d("EnglishMessageHandler", "sendSmsMessage: number=$contactNumber message='$message'")
        
        if (contactNumber.isBlank() || message.isBlank()) {
            Log.e("EnglishMessageHandler", "Empty contact number or message")
            return false
        }
        
        return try {
            // Open SMS app with pre-filled message (same as our working SMS method)
            val uri = Uri.parse("smsto:$contactNumber")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("EnglishMessageHandler", "SMS app opened successfully")
            true
        } catch (e: Exception) {
            Log.e("EnglishMessageHandler", "Failed to open SMS app: ${e.message}", e)
            false
        }
    }
    
    /**
     * Send WhatsApp message in English directly - opens WhatsApp with pre-filled message  
     */
    fun sendWhatsAppMessage(contactNumber: String, message: String): Boolean {
        Log.d("EnglishMessageHandler", "sendWhatsAppMessage: number=$contactNumber message='$message'")
        
        if (contactNumber.isBlank() || message.isBlank()) {
            Log.e("EnglishMessageHandler", "Empty contact number or message")
            return false
        }
        
        return try {
            // Clean the phone number
            val cleanNumber = contactNumber.replace(Regex("[^0-9+]"), "")
            
            // WhatsApp URL scheme - direct to chat with pre-filled message
            val whatsappUrl = "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(whatsappUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("EnglishMessageHandler", "WhatsApp opened successfully")
            true
        } catch (e: Exception) {
            Log.e("EnglishMessageHandler", "Failed to open WhatsApp: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if SMS permissions are available
     */
    fun hasSmsPermissions(): Boolean {
        return deviceActions.hasSendSmsPermission()
    }
    
    /**
     * Find contact number by name (for contact name resolution)
     */
    fun findContactNumber(contactName: String): String? {
        return if (deviceActions.hasReadContactsPermission()) {
            deviceActions.findPhoneNumberByName(contactName)
        } else {
            null
        }
    }
}
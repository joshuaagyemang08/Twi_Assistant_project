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
     * Format phone number for WhatsApp international format
     * Handles Ghana phone numbers and converts them to proper international format
     * Examples:
     * - "0245876597" -> "233245876597"
     * - "+233245876597" -> "233245876597" 
     * - "233245876597" -> "233245876597"
     * - "+0245876597" -> "233245876597" (fixes invalid format)
     */
    private fun formatPhoneNumberForWhatsApp(number: String): String {
        // Remove all non-digit characters except +
        var cleanNumber = number.replace(Regex("[^0-9+]"), "")
        
        // Remove leading + if present
        if (cleanNumber.startsWith("+")) {
            cleanNumber = cleanNumber.substring(1)
        }
        
        // Handle Ghana phone numbers
        return when {
            // If already starts with 233 (Ghana country code)
            cleanNumber.startsWith("233") -> cleanNumber
            
            // If starts with 0 (local Ghana format) - convert to international
            cleanNumber.startsWith("0") && cleanNumber.length >= 10 -> {
                "233" + cleanNumber.substring(1) // Replace 0 with 233
            }
            
            // If it's a Ghana mobile number without country code or leading 0
            // Ghana mobile numbers typically start with: 20, 24, 25, 26, 27, 28, 50, 54, 55, 56, 57, 59
            cleanNumber.length == 9 && cleanNumber.matches(Regex("(20|2[4-8]|5[04-79])\\d{7}")) -> {
                "233$cleanNumber"
            }
            
            // For any number that might be incorrectly formatted (like +0245876597)
            // Extract the core Ghana number and reformat
            cleanNumber.matches(Regex("0?(2[0-9]|5[0-9])\\d{7}")) -> {
                val coreNumber = if (cleanNumber.startsWith("0")) {
                    cleanNumber.substring(1)
                } else {
                    cleanNumber
                }
                "233$coreNumber"
            }
            
            // Default: return as is if we can't identify the format
            else -> cleanNumber
        }
    }
    
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
            // Format the phone number to international format for WhatsApp
            val formattedNumber = formatPhoneNumberForWhatsApp(contactNumber)
            
            // WhatsApp URL scheme - direct to chat with pre-filled message
            val whatsappUrl = "https://api.whatsapp.com/send?phone=$formattedNumber&text=${Uri.encode(message)}"
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
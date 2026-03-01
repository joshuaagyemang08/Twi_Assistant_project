package com.example.twiassistant.device_control

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.app.SearchManager
import android.util.Log
import java.text.Normalizer

class DeviceActions(private val context: Context) {
    
    // Expose context for other components that need it
    val applicationContext: Context get() = context

    data class ContactMatch(
        val displayName: String,
        val number: String,
        val score: Double,
    )

    fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasSendSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun findPhoneNumberByName(name: String): String? {
        val queryName = name.trim()
        if (queryName.isBlank()) return null

        if (!hasReadContactsPermission()) {
            throw SecurityException("Missing android.permission.READ_CONTACTS")
        }

        val resolver: ContentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$queryName%")

        // Fast path: simple partial match using LIKE.
        resolver.query(uri, projection, selection, args, null).use { cursor ->
            if (cursor == null) return null
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIdx)?.trim()
                if (!number.isNullOrBlank()) return number
            }
        }

        // Fallback: fuzzy-match across contacts to handle ASR spelling/diacritic differences.
        val best = findPhoneCandidatesByName(queryName, maxResults = 1).firstOrNull()
        // Tuneable threshold: keep conservative to avoid calling the wrong person.
        return if (best != null && best.score >= 0.72) best.number else null
    }

    fun findPhoneCandidatesByName(name: String, maxResults: Int = 2): List<ContactMatch> {
        val queryName = name.trim()
        if (queryName.isBlank()) return emptyList()

        if (!hasReadContactsPermission()) {
            throw SecurityException("Missing android.permission.READ_CONTACTS")
        }

        val normalizedQuery = normalizeName(queryName)
        if (normalizedQuery.isBlank()) return emptyList()

        val resolver: ContentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        val matches = ArrayList<ContactMatch>(maxResults.coerceAtLeast(2))
        val seenNumbers = HashSet<String>()

        resolver.query(uri, projection, null, null, null).use { cursor ->
            if (cursor == null) return emptyList()
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIdx)?.trim().orEmpty()
                val number = cursor.getString(numberIdx)?.trim().orEmpty()
                if (displayName.isBlank() || number.isBlank()) continue
                if (!seenNumbers.add(number)) continue

                val normalizedCandidate = normalizeName(displayName)
                if (normalizedCandidate.isBlank()) continue

                val score = similarity(normalizedQuery, normalizedCandidate)
                if (matches.size < maxResults) {
                    matches.add(ContactMatch(displayName = displayName, number = number, score = score))
                } else {
                    // Replace the current worst match if this is better.
                    var worstIdx = 0
                    var worstScore = matches[0].score
                    for (i in 1 until matches.size) {
                        if (matches[i].score < worstScore) {
                            worstScore = matches[i].score
                            worstIdx = i
                        }
                    }
                    if (score > worstScore) {
                        matches[worstIdx] = ContactMatch(displayName = displayName, number = number, score = score)
                    }
                }
            }
        }

        return matches.sortedByDescending { it.score }.take(maxResults)
    }

    private fun normalizeName(raw: String): String {
        // Lowercase + remove accents/diacritics (e.g., ɔ/ɛ variants may survive depending on Unicode,
        // but this still helps for many ASR differences).
        val nfd = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        val withoutMarks = nfd.replace(Regex("\\p{Mn}+"), "")
        return withoutMarks
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0.0
        val dist = levenshteinDistance(a, b)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Ensure b is the shorter string to reduce memory.
        var s1 = a
        var s2 = b
        if (s1.length < s2.length) {
            val tmp = s1
            s1 = s2
            s2 = tmp
        }

        val prev = IntArray(s2.length + 1) { it }
        val curr = IntArray(s2.length + 1)

        for (i in s1.indices) {
            curr[0] = i + 1
            val c1 = s1[i]
            for (j in s2.indices) {
                val cost = if (c1 == s2[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            for (k in prev.indices) prev[k] = curr[k]
        }

        return prev[s2.length]
    }

    fun setAlarm(timeText: String): Boolean {
        val parsed = parseTime(timeText) ?: return false
        val (hour, minute) = parsed
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun adjustBrightness(action: com.example.twiassistant.nlu.BrightnessAction, percent: Int? = null): Boolean {
        // Brightness control removed - not needed for simplified assistant (Calls, Messages, Apps, Adesua only)
        return false
    }

    // Helper to check if we have WRITE_SETTINGS permission - disabled
    fun canWriteSettings(): Boolean = false

    // System settings permission no longer needed - feature disabled
    fun openWriteSettingsPermission() {
        // Feature disabled for simplified assistant
    }

    fun dialOrCall(number: String) {
        if (number.isBlank()) return
        val hasCallPermission =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun sendSms(number: String, body: String): Boolean {
        if (number.isBlank() || body.isBlank()) return false
        
        return try {
            // Open SMS app with pre-filled message (like WhatsApp)
            val uri = Uri.parse("smsto:$number")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("DeviceActions", "Failed to open SMS app: ${e.message}", e)
            false
        }
    }

    fun sendSmsDirectly(number: String, body: String): Boolean {
        // Silent SMS sending using SmsManager (backup method)
        if (number.isBlank() || body.isBlank()) return false
        
        // Check for SMS sending permission
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DeviceActions", "Missing SEND_SMS permission")
            return false
        }
        
        return try {
            val smsManager = SmsManager.getDefault()
            val cleanNumber = number.replace(Regex("[^0-9+]"), "")
            
            // For long messages, divide into parts
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(cleanNumber, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            }
            
            Log.d("DeviceActions", "SMS sent successfully to $cleanNumber")
            true
        } catch (e: Exception) {
            Log.e("DeviceActions", "Failed to send SMS: ${e.message}", e)
            false
        }
    }

    fun sendSmsWithIntent(number: String, body: String) {
        // Keep the old method as backup - opens SMS app
        if (number.isBlank()) return
        val uri = Uri.parse("smsto:$number")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun sendWhatsAppMessage(number: String, body: String): Boolean {
        if (number.isBlank() || body.isBlank()) return false
        
        return try {
            // Clean the number (remove spaces, dashes, etc.)
            val cleanNumber = number.replace(Regex("[^0-9+]"), "")
            
            // Try WhatsApp with tel: scheme first (more reliable)
            val whatsappUri = "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(body)}"
            val whatsappIntent = Intent(Intent.ACTION_VIEW, Uri.parse(whatsappUri)).apply {
                setPackage("com.whatsapp") // Target WhatsApp specifically
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // Check if WhatsApp is installed
            val packageManager = context.packageManager
            if (whatsappIntent.resolveActivity(packageManager) != null) {
                context.startActivity(whatsappIntent)
                true
            } else {
                // WhatsApp not installed, try WhatsApp Business as fallback
                val whatsappBusinessIntent = Intent(Intent.ACTION_VIEW, Uri.parse(whatsappUri)).apply {
                    setPackage("com.whatsapp.w4b")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (whatsappBusinessIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(whatsappBusinessIntent)
                    true
                } else {
                    false // No WhatsApp found
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    data class SmsMessage(val address: String, val body: String, val timestamp: Long)

    fun getUnreadMessages(limit: Int = 5): List<SmsMessage> {
        // Requires READ_SMS; callers must have permission.
        val resolver = context.contentResolver
        val projection = arrayOf("address", "body", "date", "read")
        val selection = "read = 0"
        val sort = "date DESC"
        return try {
            resolver.query(Uri.parse("content://sms/inbox"), projection, selection, null, sort).use { cursor ->
                if (cursor == null) return emptyList()
                val addrIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                val list = ArrayList<SmsMessage>(limit)
                while (cursor.moveToNext() && list.size < limit) {
                    val address = cursor.getString(addrIdx)?.ifBlank { "(unknown)" } ?: "(unknown)"
                    val body = cursor.getString(bodyIdx)?.trim().orEmpty()
                    val ts = cursor.getLong(dateIdx)
                    list.add(SmsMessage(address = address, body = body, timestamp = ts))
                }
                list
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun launchAppByName(appName: String): Boolean {
        if (appName.isBlank()) return false
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        
        // Try exact match first (case-insensitive)
        val exactMatch = apps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString()
            label.equals(appName, ignoreCase = true)
        }
        
        // If exact match found, launch it
        if (exactMatch != null) {
            val launchIntent = pm.getLaunchIntentForPackage(exactMatch.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return true
            }
        }
        
        // Try partial match (contains)
        val match = apps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString()
            label.contains(appName, ignoreCase = true) || it.packageName.contains(appName, ignoreCase = true)
        } ?: return false
        
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
    
    /**
     * Get detailed app information for better matching
     * Returns map of app name to package name
     */
    fun getAllLaunchableApps(): Map<String, String> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val launchableApps = mutableMapOf<String, String>()
        
        for (appInfo in apps) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    launchableApps[label] = appInfo.packageName
                }
            } catch (e: Exception) {
                // Skip apps that can't be launched
            }
        }
        
        return launchableApps
    }
    
    /**
     * Find best matching apps for a given name
     * Returns list of app names sorted by match quality
     */
    fun findMatchingApps(searchName: String, maxResults: Int = 5): List<String> {
        if (searchName.isBlank()) return emptyList()
        
        val allApps = getAllLaunchableApps()
        val searchLower = searchName.lowercase().trim()
        
        // Score each app based on match quality
        val scoredApps = allApps.keys.mapNotNull { appName ->
            val appLower = appName.lowercase()
            val score = when {
                appLower == searchLower -> 100 // Exact match
                appLower.startsWith(searchLower) -> 90 // Starts with
                appLower.endsWith(searchLower) -> 70 // Ends with
                appLower.contains(searchLower) -> 60 // Contains
                searchLower.split(" ").any { word -> appLower.contains(word) && word.length > 2 } -> 40 // Word match
                else -> null
            }
            if (score != null) appName to score else null
        }
        
        // Sort by score (highest first) and return top results
        return scoredApps
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }
    }
    
    /**
     * Launch app by package name (more reliable than name)
     */
    fun launchAppByPackageName(packageName: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    fun getInstalledAppNames(): List<String> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        return apps.mapNotNull { appInfo ->
            try {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    pm.getApplicationLabel(appInfo).toString()
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseTime(timeText: String): Pair<Int, Int>? {
        val t = timeText.trim().lowercase()
        if (t.isBlank()) return null

        // Formats: 19:30, 07:10
        Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(t)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: return null
            val m = it.groupValues[2].toIntOrNull() ?: return null
            return h to m
        }

        // Formats: 7am, 7 am, 10pm
        Regex("^(1[0-2]|0?[1-9])\\s?(am|pm)$").find(t)?.let {
            var h = it.groupValues[1].toIntOrNull() ?: return null
            val ap = it.groupValues[2]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return h to 0
        }

        // Just an hour: "7" => 07:00
        Regex("^(1[0-2]|0?[0-9]|2[0-3])$").find(t)?.let {
            val h = it.value.toIntOrNull() ?: return null
            return h.coerceIn(0, 23) to 0
        }

        return null
    }
}

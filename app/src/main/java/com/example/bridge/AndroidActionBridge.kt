package com.example.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.model.ActionResult
import com.example.model.ActionStatus
import com.example.model.ContactMatch
import java.util.Locale

object AndroidActionBridge {

    fun openWhatsApp(context: Context): ActionResult {
        val pm = context.packageManager
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in packages) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(launchIntent)
                    ActionResult(
                        success = true,
                        message = "WhatsApp opened successfully.",
                        actionName = "openWhatsApp",
                        target = "WhatsApp",
                        status = ActionStatus.SUCCESS
                    )
                } catch (e: Exception) {
                    ActionResult(
                        success = false,
                        message = "Error launching WhatsApp: ${e.localizedMessage}",
                        actionName = "openWhatsApp",
                        target = "WhatsApp",
                        status = ActionStatus.ERROR
                    )
                }
            }
        }

        // Deep link fallback
        return try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (deepLinkIntent.resolveActivity(pm) != null) {
                context.startActivity(deepLinkIntent)
                ActionResult(
                    success = true,
                    message = "WhatsApp opened via deep link.",
                    actionName = "openWhatsApp",
                    target = "WhatsApp",
                    status = ActionStatus.SUCCESS
                )
            } else {
                ActionResult(
                    success = false,
                    message = "WhatsApp is not installed on this device.",
                    actionName = "openWhatsApp",
                    target = "WhatsApp",
                    status = ActionStatus.ERROR
                )
            }
        } catch (e: Exception) {
            ActionResult(
                success = false,
                message = "WhatsApp is not installed on this device.",
                actionName = "openWhatsApp",
                target = "WhatsApp",
                status = ActionStatus.ERROR
            )
        }
    }

    fun openApp(context: Context, appName: String): ActionResult {
        val cleanName = appName.trim().lowercase(Locale.ROOT)
        val pm = context.packageManager

        // Special system intents
        if (cleanName.contains("setting")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(
                    success = true,
                    message = "Device Settings opened.",
                    actionName = "openApp",
                    target = "Settings",
                    status = ActionStatus.SUCCESS
                )
            } catch (e: Exception) {
                ActionResult(false, "Failed to open Settings: ${e.message}", "openApp", "Settings", ActionStatus.ERROR)
            }
        }

        if (cleanName.contains("camera")) {
            return try {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Camera opened.", "openApp", "Camera", ActionStatus.SUCCESS)
            } catch (e: Exception) {
                // Try launch by known camera apps or fallback
                openInstalledAppByQuery(context, "camera", "Camera")
            }
        }

        // Known standard package mappings
        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "calculator" to "com.google.android.calculator",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "google play" to "com.android.vending",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "clock" to "com.google.android.deskclock",
            "whatsapp" to "com.whatsapp"
        )

        for ((key, pkg) in knownPackages) {
            if (cleanName.contains(key)) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return try {
                        context.startActivity(launchIntent)
                        ActionResult(true, "$key opened successfully.", "openApp", key, ActionStatus.SUCCESS)
                    } catch (e: Exception) {
                        ActionResult(false, "Could not launch $key: ${e.message}", "openApp", key, ActionStatus.ERROR)
                    }
                }
            }
        }

        // Search all installed packages dynamically by app label
        return openInstalledAppByQuery(context, cleanName, appName)
    }

    private fun openInstalledAppByQuery(context: Context, query: String, originalName: String): ActionResult {
        val pm = context.packageManager
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedList = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolvedList) {
                val appLabel = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
                val packageName = info.activityInfo.packageName
                if (appLabel.contains(query) || packageName.lowercase(Locale.ROOT).contains(query)) {
                    val launchIntent = pm.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        val displayLabel = info.loadLabel(pm).toString()
                        return ActionResult(
                            success = true,
                            message = "$displayLabel opened successfully.",
                            actionName = "openApp",
                            target = displayLabel,
                            status = ActionStatus.SUCCESS
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through
        }

        // Web fallback for YouTube / Chrome / Maps
        if (query.contains("youtube")) {
            return openUrl(context, "https://youtube.com")
        }

        return ActionResult(
            success = false,
            message = "App '$originalName' is not installed on this device.",
            actionName = "openApp",
            target = originalName,
            status = ActionStatus.ERROR
        )
    }

    fun openUrl(context: Context, url: String): ActionResult {
        val cleanUrl = url.trim()
        val formattedUrl = if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            "https://$cleanUrl"
        } else {
            cleanUrl
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult(
                success = true,
                message = "Opened $formattedUrl in browser.",
                actionName = "openUrl",
                target = formattedUrl,
                status = ActionStatus.SUCCESS
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                message = "Unable to open link: ${e.message}",
                actionName = "openUrl",
                target = formattedUrl,
                status = ActionStatus.ERROR
            )
        }
    }

    fun makeCall(context: Context, phoneNumber: String): ActionResult {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) {
            return ActionResult(
                success = false,
                message = "Invalid phone number provided.",
                actionName = "makeCall",
                target = phoneNumber,
                status = ActionStatus.ERROR
            )
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            val actionType = if (hasCallPermission) "Calling" else "Opened dialer for"
            ActionResult(
                success = true,
                message = "$actionType $cleanNumber",
                actionName = "makeCall",
                target = cleanNumber,
                status = ActionStatus.SUCCESS
            )
        } catch (e: Exception) {
            // Fallback to dialer
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ActionResult(
                    success = true,
                    message = "Opened dialer with $cleanNumber",
                    actionName = "makeCall",
                    target = cleanNumber,
                    status = ActionStatus.SUCCESS
                )
            } catch (ex: Exception) {
                ActionResult(
                    success = false,
                    message = "Failed to start call: ${ex.message}",
                    actionName = "makeCall",
                    target = cleanNumber,
                    status = ActionStatus.ERROR
                )
            }
        }
    }

    fun searchContacts(context: Context, contactName: String): List<ContactMatch> {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return emptyList()
        }

        val matches = mutableListOf<ContactMatch>()
        val cleanQuery = contactName.trim().lowercase(Locale.ROOT)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.let {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (it.moveToNext()) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val number = if (numberIdx >= 0) it.getString(numberIdx) ?: "" else ""
                    val id = if (idIdx >= 0) it.getString(idIdx) ?: "" else ""
                    val typeCode = if (typeIdx >= 0) it.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val typeStr = when (typeCode) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Mobile"
                    }

                    if (name.isNotBlank() && number.isNotBlank()) {
                        val lowerName = name.lowercase(Locale.ROOT)
                        // Match full name, first name, substring, or exact aliases (e.g. mummy -> mom)
                        if (matchesNameQuery(lowerName, cleanQuery)) {
                            // Deduplicate by number
                            if (matches.none { m -> m.phoneNumber.replace(Regex("[^0-9]"), "") == number.replace(Regex("[^0-9]"), "") }) {
                                matches.add(ContactMatch(id = id, name = name, phoneNumber = number, type = typeStr))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Permission or content resolver error
        } finally {
            cursor?.close()
        }

        return matches
    }

    private fun matchesNameQuery(contactName: String, query: String): Boolean {
        if (contactName == query) return true
        if (contactName.contains(query)) return true
        val words = contactName.split(" ", "-", "_")
        if (words.any { it == query || it.startsWith(query) }) return true

        // Multilingual / Hindi relationship aliases
        val aliases = mapOf(
            "mom" to listOf("mummy", "maa", "mother", "amma"),
            "mummy" to listOf("mom", "maa", "mother", "amma"),
            "dad" to listOf("papa", "father", "daddy", "abba"),
            "papa" to listOf("dad", "father", "daddy", "abba"),
            "bhai" to listOf("brother", "bro"),
            "sister" to listOf("didi", "sis", "behan")
        )

        for ((key, aliasList) in aliases) {
            if (query == key && aliasList.any { contactName.contains(it) }) return true
            if (aliasList.contains(query) && contactName.contains(key)) return true
        }

        return false
    }

    fun callContact(context: Context, contactName: String): ActionResult {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return ActionResult(
                success = false,
                message = "Contacts permission is required to search and call '$contactName'. Please grant contacts access.",
                actionName = "callContact",
                target = contactName,
                status = ActionStatus.ERROR
            )
        }

        val matches = searchContacts(context, contactName)

        return when {
            matches.isEmpty() -> {
                ActionResult(
                    success = false,
                    message = "I found no contact named '$contactName' in your phone contacts.",
                    actionName = "callContact",
                    target = contactName,
                    status = ActionStatus.ERROR
                )
            }
            matches.size == 1 -> {
                val target = matches[0]
                val callRes = makeCall(context, target.phoneNumber)
                ActionResult(
                    success = callRes.success,
                    message = "Found ${target.name}. Calling ${target.phoneNumber}...",
                    actionName = "callContact",
                    target = "${target.name} (${target.phoneNumber})",
                    status = if (callRes.success) ActionStatus.SUCCESS else ActionStatus.ERROR,
                    contactsList = matches
                )
            }
            else -> {
                val listSummary = matches.joinToString(", ") { "${it.name} (${it.phoneNumber})" }
                ActionResult(
                    success = false,
                    message = "I found ${matches.size} contacts for '$contactName': $listSummary. Which one should I call?",
                    actionName = "callContact",
                    target = contactName,
                    status = ActionStatus.NEED_CLARIFICATION,
                    contactsList = matches
                )
            }
        }
    }
}

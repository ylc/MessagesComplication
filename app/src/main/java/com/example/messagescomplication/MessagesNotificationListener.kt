package com.example.messagescomplication

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MessagesNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MessagesBadge"
        const val MESSAGES_PKG = "com.google.android.apps.messaging"
        const val PREFS_NAME = "messages_badge_prefs"
        const val KEY_UNREAD_COUNT = "unread_count"
        const val KEY_COUNT_INDIVIDUAL = "count_individual_messages"

        // Set to false for unread conversations count (WhatsApp style)
        // Set to true for total unread messages across all threads (Gmail style)
        private const val COUNT_INDIVIDUAL_MESSAGES = true

        private val dumpLock = Any()

        private val STREAM_ITEM_REGEX = Regex(
            """StreamItemIdAndRevision\{packageName=com\.google\.android\.apps\.messaging,\s*id=\d+,\s*revision=(\d+),\s*originalRevision=(\d+),\s*updateCount=(\d+)\}"""
        )

        fun getStoredCount(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_UNREAD_COUNT, 0)
        }

        fun isCountingIndividualMessages(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COUNT_INDIVIDUAL, COUNT_INDIVIDUAL_MESSAGES)
        }

        fun setCountingIndividualMessages(context: Context, countIndividual: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COUNT_INDIVIDUAL, countIndividual)
                .apply()
            requestComplicationUpdate(context)
        }

        fun requestComplicationUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester.create(
                context = context.applicationContext,
                complicationDataSourceComponent = ComponentName(
                    context.applicationContext,
                    UnreadMessagesComplicationService::class.java
                )
            ).requestUpdateAll()
        }

        /**
         * Reads Wear OS SysUI NotificationTrayDataStore via IBinder.dump on the "activity" service
         * to count bridged Google Messages notifications that live in the Wear OS notification
         * stream rather than the local Android NotificationManager.
         *
         * Returns null if DUMP permission is not granted or SysUI dump timed out / was unavailable.
         */
        fun querySysUiTrayMessagesCount(countIndividual: Boolean): Int? = synchronized(dumpLock) {
            return try {
                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val getService = serviceManagerClass.getMethod("getService", String::class.java)
                val activityBinder = getService.invoke(null, "activity") as? IBinder
                if (activityBinder == null) {
                    Log.w(TAG, "Could not obtain activity IBinder")
                    return null
                }

                val pipe = ParcelFileDescriptor.createPipe()
                val readSide = pipe[0]
                val writeSide = pipe[1]

                val dumpThread = Thread {
                    try {
                        activityBinder.dump(
                            writeSide.fileDescriptor,
                            arrayOf(
                                "service",
                                "com.google.android.wearable.sysui/com.google.android.clockwork.sysui.mainui.module.dumpsys.DumpsysService"
                            )
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in activityBinder.dump", e)
                    } finally {
                        runCatching { writeSide.close() }
                    }
                }
                dumpThread.start()

                val updateCounts = mutableListOf<Int>()
                var inTraySection = false
                var firstLine: String? = null

                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    BufferedReader(InputStreamReader(input)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (firstLine == null && l.isNotBlank()) {
                                firstLine = l
                            }
                            if (l.contains("NotificationTrayDataStore")) {
                                inTraySection = true
                            } else if (inTraySection && l.contains("#####")) {
                                break
                            } else if (inTraySection && l.contains(MESSAGES_PKG)) {
                                val match = STREAM_ITEM_REGEX.find(l)
                                if (match != null) {
                                    val updateCount = match.groupValues[3].toIntOrNull() ?: 0
                                    updateCounts.add(updateCount)
                                }
                            }
                        }
                    }
                }
                dumpThread.join(3000L)

                if (!inTraySection) {
                    Log.w(TAG, "SysUI NotificationTrayDataStore section not found (firstLine=$firstLine)")
                    return null
                }

                calculateCountFromStreamItems(updateCounts, countIndividual)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to query SysUI tray via IBinder.dump", e)
                null
            }
        }

        private fun calculateCountFromStreamItems(
            updateCounts: List<Int>,
            countIndividual: Boolean
        ): Int {
            if (updateCounts.isEmpty()) return 0

            // When Google Messages bridges notifications from the phone:
            // - 1 item means 1 conversation thread (with 1 + updateCount messages).
            // - 2+ items means 1 group summary container (updateCount == 0) + (N - 1) conversation threads.
            val conversationUpdateCounts = if (updateCounts.size >= 2 && updateCounts.contains(0)) {
                val mutable = updateCounts.toMutableList()
                mutable.remove(0) // Remove the 1 group summary entry
                mutable
            } else {
                updateCounts
            }

            return if (countIndividual) {
                conversationUpdateCounts.sumOf { 1 + it }
            } else {
                conversationUpdateCounts.size
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            executor.execute { recalculateAndPush() }
            val powerManager = getSystemService(PowerManager::class.java)
            val intervalMs = if (powerManager?.isInteractive == true) 4_000L else 12_000L
            handler.postDelayed(this, intervalMs)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                handler.removeCallbacks(pollRunnable)
                handler.post(pollRunnable)
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_COUNT_INDIVIDUAL) {
            executor.execute { recalculateAndPush() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacks(pollRunnable)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(pollRunnable)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        executor.execute { recalculateAndPush() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        executor.execute { recalculateAndPush() }
    }

    private fun recalculateAndPush() {
        val countIndividual = isCountingIndividualMessages(this)

        // 1. Check local watch notifications (used when watch is standalone on LTE/Wi-Fi)
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (e: SecurityException) {
            emptyArray()
        }

        val allMessagesNotifications = active.filter { sbn ->
            val flags = sbn.notification.flags
            val isOngoing = (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
            sbn.packageName == MESSAGES_PKG && !isOngoing
        }

        val nonSummaryNotifications = allMessagesNotifications.filter { sbn ->
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }
        val conversationNotifications = nonSummaryNotifications.ifEmpty { allMessagesNotifications }

        val localCount = if (countIndividual) {
            conversationNotifications.sumOf { sbn ->
                val messagesArray = sbn.notification.extras
                    ?.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
                val number = sbn.notification.number
                when {
                    !messagesArray.isNullOrEmpty() -> messagesArray.size
                    number > 1 -> number
                    else -> 1
                }
            }
        } else {
            conversationNotifications.size
        }

        // 2. Check bridged phone notifications in Wear OS SysUI NotificationTrayDataStore
        val trayCount = querySysUiTrayMessagesCount(countIndividual)
        if (trayCount == null && localCount == 0) {
            // SysUI dump timed out or was temporarily unavailable; preserve the last known count
            // rather than falsely resetting the complication to 0.
            return
        }

        val unreadCount = maxOf(localCount, trayCount ?: 0)
        val previousCount = getStoredCount(this)

        if (unreadCount != previousCount) {
            Log.d(TAG, "Unread count changed: $previousCount -> $unreadCount (local=$localCount, tray=$trayCount, individual=$countIndividual)")
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_UNREAD_COUNT, unreadCount)
                .apply()
            requestComplicationUpdate(applicationContext)
        }
    }
}

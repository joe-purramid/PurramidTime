// BootReceiver.kt
package com.example.purramid.purramidtime.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.purramid.purramidtime.data.db.ClockAlarmDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reschedules all enabled Clock alarms after a device reboot.
 * AlarmManager forgets every alarm across a reboot, so without this an "active" alarm
 * (spec 2.6.2: active alarms remain active on next launch) would silently never fire.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmDao: ClockAlarmDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        // Keep the receiver alive while we read the DB and reschedule off the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarms = alarmDao.getAllActiveAlarms().first()
                alarms.forEach { AlarmScheduler.schedule(appContext, it) }
                Log.d(TAG, "Rescheduled ${alarms.size} alarm(s) after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarms after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

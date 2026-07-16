// AlarmScheduler.kt
package com.example.purramid.purramidtime.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.purramid.purramidtime.data.db.ClockAlarmEntity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Single place that owns AlarmManager scheduling/cancelling for Clock alarms.
 * Shared by [ClockAlarmActivity] (on save) and [BootReceiver] (reschedule after reboot)
 * so the PendingIntent request code and extras stay identical (required for cancel to match).
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_CLOCK_ID = "clock_id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_SOUND_ENABLED = "sound_enabled"
    const val EXTRA_VIBRATION_ENABLED = "vibration_enabled"
    const val EXTRA_TIME_ZONE_ID = "time_zone_id"

    /**
     * Schedules [alarm] with the system AlarmManager.
     * @return true if scheduled exactly; false if the app lacks exact-alarm permission
     *         and fell back to an inexact (allow-while-idle) alarm.
     */
    fun schedule(context: Context, alarm: ClockAlarmEntity): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, alarm)
        val triggerTime = calculateTriggerTime(alarm)

        // CLAUDE.md: SCHEDULE_EXACT_ALARM is user-revocable on Android 13; guard before scheduling.
        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        return try {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact alarm ${alarm.alarmId} for ${alarm.time} (${alarm.timeZoneId})")
                true
            } else {
                // Graceful fallback: still fire, just not guaranteed to-the-minute.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.w(TAG, "Exact alarms not permitted; scheduled inexact alarm ${alarm.alarmId}")
                false
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            Log.w(TAG, "SecurityException scheduling exact alarm ${alarm.alarmId}; fell back to inexact", e)
            false
        }
    }

    fun cancel(context: Context, alarmId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Cancelled alarm $alarmId")
        }
    }

    private fun buildPendingIntent(context: Context, alarm: ClockAlarmEntity): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.alarmId)
            putExtra(EXTRA_CLOCK_ID, alarm.instanceId)
            putExtra(EXTRA_LABEL, alarm.label)
            putExtra(EXTRA_SOUND_ENABLED, alarm.soundEnabled)
            putExtra(EXTRA_VIBRATION_ENABLED, alarm.vibrationEnabled)
            putExtra(EXTRA_TIME_ZONE_ID, alarm.timeZoneId ?: ZoneId.systemDefault().id)
        }
        return PendingIntent.getBroadcast(
            context,
            alarm.alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun calculateTriggerTime(alarm: ClockAlarmEntity): Long {
        val timeZone = ZoneId.of(alarm.timeZoneId ?: ZoneId.systemDefault().id)
        val now = ZonedDateTime.now(timeZone)
        val today = now.toLocalDate()
        val alarmDateTime = LocalDateTime.of(today, alarm.time)
        var alarmZonedDateTime = ZonedDateTime.of(alarmDateTime, timeZone)

        // If the alarm time has already passed today, schedule for tomorrow.
        if (alarmZonedDateTime.isBefore(now)) {
            alarmZonedDateTime = alarmZonedDateTime.plusDays(1)
        }
        return alarmZonedDateTime.toInstant().toEpochMilli()
    }
}

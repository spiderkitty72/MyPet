package com.mypet.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mypet.core.CallKind
import com.mypet.core.PetGame
import com.mypet.core.SaveData

/**
 * Background calls. Because the rules are deterministic, we can work out exactly when
 * the pet will next call and set one alarm for that moment, instead of polling.
 */
object PetAlarms {
    private const val CHANNEL_ID = "pet_calls"
    private const val NOTIFICATION_ID = 1

    /** True while the main screen is visible; the screen handles calls itself then. */
    @Volatile
    var inForeground: Boolean = false

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Sets (or clears) the alarm for the pet's next call based on [save]. */
    fun schedule(context: Context, save: SaveData) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context)
        alarms.cancel(intent)
        if (!save.settings.notifications) return
        val game = PetGame(save)
        val minutes = game.minutesUntilNextCall() ?: return
        val speed = save.settings.speed.coerceIn(1, 4)
        val delay = (minutes * PetGame.MILLIS_PER_MINUTE - save.carryMillis).coerceAtLeast(0) / speed
        // A little slack so the simulation has definitely reached the call when we wake.
        val at = save.lastRealMillis + delay + 5_000
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    /** Called from the alarm: catch the pet up, notify if it is calling, and re-arm. */
    fun check(context: Context) {
        if (inForeground) return
        val store = PetStore(context)
        val save = store.load()
        val game = PetGame(save)
        game.advanceTo(System.currentTimeMillis())
        store.save(save)
        val pet = save.pet
        if (pet != null && save.settings.notifications) {
            val key = when {
                !pet.alive -> "end@${pet.endedAtMinute}"
                pet.call != null -> "${pet.call}@${pet.callStartMinute}"
                else -> null
            }
            if (key != null && key != store.lastNotifiedCall) {
                store.lastNotifiedCall = key
                notify(context, pet.name, pet.call, pet.alive, pet.edition.rules.gentleEndings)
            }
        }
        schedule(context, save)
    }

    fun clearNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, name: String, call: CallKind?, alive: Boolean, gentle: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val text = when {
            !alive && gentle -> "$name has packed up and headed home."
            !alive -> "$name's journey has ended."
            else -> when (call) {
                CallKind.HUNGER -> "$name is hungry!"
                CallKind.HAPPY -> "$name is bored and wants to play."
                CallKind.SICK -> "$name feels sick. Medicine time."
                CallKind.LIGHTS -> "$name is sleepy. Turn off the light."
                CallKind.FAKE, null -> "$name is calling you."
            }
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle("MyPet")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, CallAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PetAlarms.check(context)
    }
}

/** Alarms are cleared on reboot; set the next one again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) PetAlarms.check(context)
    }
}

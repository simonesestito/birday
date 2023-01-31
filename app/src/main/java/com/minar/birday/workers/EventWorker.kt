package com.minar.birday.workers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.minar.birday.R
import com.minar.birday.activities.MainActivity
import com.minar.birday.model.EventResult
import com.minar.birday.persistence.EventDao
import com.minar.birday.persistence.EventDatabase
import com.minar.birday.receivers.NotificationActionReceiver
import com.minar.birday.utilities.byteArrayToBitmap
import com.minar.birday.utilities.formatEventList
import com.minar.birday.utilities.getCircularBitmap
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit

class EventWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val appContext = applicationContext
        val eventDao: EventDao = EventDatabase.getBirdayDatabase(appContext).eventDao()
        val allEvents: List<EventResult> = eventDao.getOrderedEventsStatic()
        val currentDate = Calendar.getInstance()
        val dueDate = Calendar.getInstance()
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val workHour = sharedPrefs.getString("notification_hour", "8")!!.toInt()
        val workMinute = sharedPrefs.getString("notification_minute", "0")!!.toInt()
        val additionalNotification = sharedPrefs.getString("additional_notification", "0")!!.toInt()
        val surnameFirst = sharedPrefs.getBoolean("surname_first", false)
        val hideImage = sharedPrefs.getBoolean("hide_images", false)
        val onlyFavoritesNotification = sharedPrefs.getBoolean("notification_only_favorites", false)
        val onlyFavoritesAdditional = sharedPrefs.getBoolean("additional_only_favorites", false)
        val angryBird = sharedPrefs.getBoolean("angry_bird", false)

        try {
            // Check for upcoming and actual birthdays and send notification
            val anticipated = mutableListOf<EventResult>()
            val actual = mutableListOf<EventResult>()
            for (event in allEvents) {
                // Fill the list of upcoming events
                if (additionalNotification != 0 &&
                    ChronoUnit.DAYS.between(LocalDate.now(), event.nextDate)
                        .toInt() == additionalNotification
                ) {
                    if (onlyFavoritesAdditional && event.favorite == false) continue
                    anticipated.add(event)
                }

                // Fill the list of events happening today
                if (event.nextDate!!.isEqual(LocalDate.now())) {
                    if (onlyFavoritesNotification && event.favorite == false) continue
                    actual.add(event)
                }
            }
            if (anticipated.isNotEmpty()) sendNotification(
                anticipated,
                1,
                surnameFirst,
                hideImage,
                true,
                angryBird = angryBird
            )
            if (actual.isNotEmpty()) sendNotification(
                actual,
                2,
                surnameFirst,
                hideImage,
                angryBird = angryBird
            )

            // Set Execution at the time specified + 15 seconds to avoid midnight problems
            dueDate.set(Calendar.HOUR_OF_DAY, workHour)
            dueDate.set(Calendar.MINUTE, workMinute)
            dueDate.set(Calendar.SECOND, 15)
            if (dueDate.before(currentDate)) dueDate.add(Calendar.HOUR_OF_DAY, 24)
            val timeDiff = dueDate.timeInMillis - currentDate.timeInMillis
            val dailyWorkRequest = OneTimeWorkRequestBuilder<EventWorker>()
                .setInitialDelay(timeDiff, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueue(dailyWorkRequest)
        } catch (e: Exception) {
            return Result.retry()
        }

        return Result.success()
    }

    // Send notification if there's one or more birthdays today
    private fun sendNotification(
        nextEvents: List<EventResult>,
        id: Int,
        surnameFirst: Boolean,
        hideImage: Boolean,
        upcoming: Boolean = false,
        angryBird: Boolean = false
    ) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Distinguish between normal notification and upcoming birthday notification
        val notificationText =
            if (!upcoming) formulateNotificationText(nextEvents, surnameFirst, angryBird)
            else formulateAdditionalNotificationText(nextEvents, surnameFirst)

        val builder = NotificationCompat.Builder(applicationContext, "events_channel")
            .setSmallIcon(if (!angryBird) R.drawable.animated_notification_icon else R.drawable.animated_angry_notification_icon)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(notificationText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notificationText)
            )
            // Intent that will fire when the user taps the notification (dismiss when angryBird is disabled)
            .setContentIntent(pendingIntent)
            .setAutoCancel(!angryBird)
            .setOngoing(angryBird)

        // When the bird is angry, the notification can only be dismissed using an action
        if (angryBird) {
            // Create an Intent for the BroadcastReceiver
            val actionIntent = Intent(applicationContext, NotificationActionReceiver::class.java)
            actionIntent.putExtra("notificationId", id)
            val actionPendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                id,
                actionIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            // Add the action to the notification
            builder.addAction(
                R.drawable.ic_clear_24dp,
                applicationContext.getString(android.R.string.ok),
                actionPendingIntent
            )
            // Action to open the dialer
            val phoneCall = Intent(Intent.ACTION_DIAL)
            val phonePendingIntent =
                PendingIntent.getActivity(
                    applicationContext,
                    id,
                    phoneCall,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            builder.addAction(
                R.drawable.ic_apps_dialer_24dp,
                applicationContext.getString(R.string.dialer),
                phonePendingIntent
            )
        }

        // If the images are shown, show the first image available in two ways
        if (!hideImage) {
            var bitmap: Bitmap? = null
            // Check if any event has an image
            for (event in nextEvents) {
                if (event.image != null)
                    bitmap = byteArrayToBitmap(event.image)
                if (bitmap != null) break
            }
            // If an image was found, set the appropriate style
            if (bitmap != null)
                with(builder) {
                    // Show the bigger picture only if the text is (presumably) short
                    if (nextEvents.size == 1)
                        setStyle(
                            NotificationCompat.BigPictureStyle()
                                .bigPicture(bitmap)
                                .bigLargeIcon(null)
                        )
                    setLargeIcon(getCircularBitmap(bitmap))
                }
        }
        with(NotificationManagerCompat.from(applicationContext)) { notify(id, builder.build()) }
    }

    // Notification for upcoming events, also considering
    private fun formulateAdditionalNotificationText(
        nextEvents: List<EventResult>,
        surnameFirst: Boolean
    ) =
        applicationContext.getString(R.string.additional_notification_text) + " " + formatEventList(
            nextEvents, surnameFirst, applicationContext
        ) + ". "

    // Notification for actual events, extended if there's one event only
    private fun formulateNotificationText(
        nextEvents: List<EventResult>,
        surnameFirst: Boolean,
        angryBird: Boolean = false
    ) =
        if (angryBird) formatEventList(nextEvents, surnameFirst, applicationContext) + "."
        else if (nextEvents.size == 1)
            applicationContext.getString(R.string.notification_description_part_1) + ": " + formatEventList(
                nextEvents, surnameFirst, applicationContext
            ) + ". " + applicationContext.getString(R.string.notification_description_part_2)
        else applicationContext.getString(R.string.notification_description_part_1) + ": " + formatEventList(
            nextEvents, surnameFirst, applicationContext
        ) + ". "

}

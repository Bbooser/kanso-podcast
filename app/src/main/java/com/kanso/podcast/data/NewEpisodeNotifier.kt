package com.kanso.podcast.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kanso.podcast.MainActivity
import com.kanso.podcast.R

/** "Nuevos episodios" notifications, one per podcast. */
object NewEpisodeNotifier {
    const val CHANNEL = "new_episodes"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Episodios nuevos", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos cuando un podcast que sigues publica un episodio"
        })
    }

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun text(podcastTitle: String, titles: List<String>): Pair<String, String> =
        if (titles.size == 1) "Nuevo episodio de $podcastTitle" to titles.first()
        else "${titles.size} episodios nuevos de $podcastTitle" to titles.take(3).joinToString(" · ")

    fun notify(context: Context, feedUrl: String, podcastTitle: String, episodeTitles: List<String>) {
        if (episodeTitles.isEmpty() || !canNotify(context)) return
        ensureChannel(context)
        val (title, body) = text(podcastTitle, episodeTitles)
        val open = PendingIntent.getActivity(context, feedUrl.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(feedUrl.hashCode(), notification) }
    }
}

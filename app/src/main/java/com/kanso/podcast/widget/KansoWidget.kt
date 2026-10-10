@file:androidx.media3.common.util.UnstableApi

package com.kanso.podcast.widget

import android.content.Context
import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import com.kanso.podcast.MainActivity
import com.kanso.podcast.playback.KansoPlaybackService
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class KansoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("now_playing", 0)
        val title = prefs.getString("title", "Nada en reproducción") ?: "Nada en reproducción"
        val podcast = prefs.getString("podcast", "Abre Kanso para escuchar") ?: "Abre Kanso para escuchar"
        val playing = prefs.getBoolean("is_playing", false)
        provideContent { PlayerWidget(title, podcast, playing) }
    }
}

@Composable private fun PlayerWidget(title: String, podcast: String, playing: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxSize().background(Color(0xFF26342F)).clickable(actionStartActivity<MainActivity>()).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(title, style = TextStyle(color = ColorProvider(Color(0xFFF5F1E8), Color(0xFFF5F1E8)), fontWeight = FontWeight.Bold, fontSize = 16.sp))
            Text(podcast, style = TextStyle(color = ColorProvider(Color(0xFFB9B8AE), Color(0xFFB9B8AE)), fontSize = 12.sp))
        }
        Text("↶", modifier = GlanceModifier.padding(8.dp).clickable(actionRunCallback<BackAction>()), style = TextStyle(color = ColorProvider(Color(0xFFF5F1E8), Color(0xFFF5F1E8)), fontSize = 22.sp))
        Text(if (playing) "Ⅱ" else "▶", modifier = GlanceModifier.padding(8.dp).clickable(actionRunCallback<ToggleAction>()), style = TextStyle(color = ColorProvider(Color(0xFFCC6B4E), Color(0xFFCC6B4E)), fontSize = 25.sp))
        Text("↷", modifier = GlanceModifier.padding(8.dp).clickable(actionRunCallback<ForwardAction>()), style = TextStyle(color = ColorProvider(Color(0xFFF5F1E8), Color(0xFFF5F1E8)), fontSize = 22.sp))
    }
}

class KansoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KansoWidget()
}

private fun control(context: Context, action: String) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, KansoPlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { player -> when (action) { "toggle" -> if (player.isPlaying) player.pause() else player.play(); "back" -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); "forward" -> player.seekTo(player.currentPosition + 30_000) }; MediaController.releaseFuture(future) } }, ContextCompat.getMainExecutor(context))
}
class ToggleAction : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) { control(context, "toggle") } }
class BackAction : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) { control(context, "back") } }
class ForwardAction : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) { control(context, "forward") } }

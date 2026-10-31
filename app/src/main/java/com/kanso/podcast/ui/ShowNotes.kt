package com.kanso.podcast.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import com.kanso.podcast.data.ShowNoteTimestamps
import com.kanso.podcast.data.db.EpisodeEntity

/** Show notes where 12:30 or 1:02:03 jump to that moment (playing the episode first if it is not the current one). */
@Composable internal fun ShowNotes(e: EpisodeEntity, player: PlayerConnection, podcastTitle: String, podcastArtwork: String?) {
    val accent = MaterialTheme.colorScheme.secondary
    val text = remember(e.id, e.description, accent) {
        val stamps = ShowNoteTimestamps.find(e.description)
        buildAnnotatedString {
            var cursor = 0
            stamps.forEach { s ->
                append(e.description.substring(cursor, s.start))
                val link = LinkAnnotation.Clickable("t${s.ms}", TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))) {
                    if (player.episodeId == e.id) player.seekTo(s.ms) else player.play(e, podcastTitle, podcastArtwork, startMs = s.ms)
                }
                withLink(link) { append(e.description.substring(s.start, s.end)) }
                cursor = s.end
            }
            append(e.description.substring(cursor))
        }
    }
    // Links are exposed to TalkBack as actions by Compose; each reads as the time itself.
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.8f))
}

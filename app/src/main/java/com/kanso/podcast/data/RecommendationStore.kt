package com.kanso.podcast.data

import android.content.Context
import com.kanso.podcast.model.PodcastSearchResult
import org.json.JSONArray
import org.json.JSONObject

/** 24 h cache of "Para ti" plus the persisted "No me interesa" list. */
class RecommendationStore(context: Context) {
    private val prefs = context.getSharedPreferences("para_ti", Context.MODE_PRIVATE)

    val dismissed: Set<String> get() = prefs.getStringSet("dismissed", emptySet()).orEmpty()
    fun dismiss(p: PodcastSearchResult) { prefs.edit().putStringSet("dismissed", dismissed + p.id + p.feedUrl).apply() }

    fun fetchedAt() = prefs.getLong("fetchedAt", 0)
    fun load(): List<Recommendation> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Recommendation(PodcastSearchResult(o.getString("id"), o.getString("title"), o.getString("author"), o.getString("feedUrl"), o.optString("art").ifBlank { null }, o.optString("genre", "Podcast")), o.getDouble("score"), o.getString("reason"))
        } }
    }.getOrDefault(emptyList())
    fun save(items: List<Recommendation>, now: Long) {
        val a = JSONArray(items.map { r -> JSONObject().put("id", r.podcast.id).put("title", r.podcast.title).put("author", r.podcast.author).put("feedUrl", r.podcast.feedUrl)
            .put("art", r.podcast.artworkUrl ?: "").put("genre", r.podcast.genre).put("score", r.score).put("reason", r.reason) })
        prefs.edit().putString("items", a.toString()).putLong("fetchedAt", now).apply()
    }
}

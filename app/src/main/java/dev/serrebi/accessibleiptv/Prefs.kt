package dev.serrebi.accessibleiptv

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Settings, sources, favourites and remembered audio tracks, kept as plain JSON in prefs. */
class Prefs(context: Context) {
	private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

	var sources: List<Source>
		get() = JSONArray(sp.getString("sources", "[]")).let { a -> List(a.length()) { Source.fromJson(a.getJSONObject(it)) } }
		set(v) = sp.edit { putString("sources", JSONArray(v.map { it.toJson() }).toString()) }

	var favorites: Set<String>
		get() = sp.getStringSet("favorites", emptySet())!!.toSet()
		set(v) = sp.edit { putStringSet("favorites", v) }

	fun toggleFavorite(key: String): Boolean {
		val now = key !in favorites
		favorites = if (now) favorites + key else favorites - key
		return now
	}

	var preferAudioDescription: Boolean
		get() = sp.getBoolean("preferAd", true)
		set(v) = sp.edit { putBoolean("preferAd", v) }

	/** Comma-separated track names or languages, most wanted first. */
	var audioKeywords: String
		get() = sp.getString("audioKeywords", "")!!
		set(v) = sp.edit { putString("audioKeywords", v) }

	var useExternalPlayer: Boolean
		get() = sp.getBoolean("externalPlayer", false)
		set(v) = sp.edit { putBoolean("externalPlayer", v) }

	/** Desktop's "Show Stream URL": adds the URL to channel details. */
	var showStreamUrl: Boolean
		get() = sp.getBoolean("showStreamUrl", false)
		set(v) = sp.edit { putBoolean("showStreamUrl", v) }

	var epgDaysAhead: Int
		get() = sp.getInt("epgDaysAhead", 2)
		set(v) = sp.edit { putInt("epgDaysAhead", v) }

	/** Which channel set the guide was built for; see [App.guideStale]. */
	var guideFingerprint: Int
		get() = sp.getInt("guideFingerprint", 0)
		set(v) = sp.edit { putInt("guideFingerprint", v) }

	var lastUpdateCheck: Long
		get() = sp.getLong("lastUpdateCheck", 0)
		set(v) = sp.edit { putLong("lastUpdateCheck", v) }

	var lastEpgImport: Long
		get() = sp.getLong("lastEpgImport", 0)
		set(v) = sp.edit { putLong("lastEpgImport", v) }

	var lastGroup: String
		get() = sp.getString("lastGroup", "")!!
		set(v) = sp.edit { putString("lastGroup", v) }

	private val tracks get() = JSONObject(sp.getString("channelTracks", "{}")!!)

	/** Track name picked by hand on a channel; comes back ahead of every other rule. */
	fun rememberedTrack(channelKey: String): String? = tracks.optString(channelKey).ifEmpty { null }

	fun rememberTrack(channelKey: String, name: String) =
		sp.edit { putString("channelTracks", tracks.put(channelKey, name).toString()) }
}

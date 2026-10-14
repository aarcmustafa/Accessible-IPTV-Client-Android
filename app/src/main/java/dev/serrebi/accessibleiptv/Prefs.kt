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

	/** Desktop internal_player_buffer_seconds: seconds buffered before playing and after a stall. */
	var bufferSeconds: Double
		get() = sp.getFloat("bufferSeconds", 6f).toDouble()
		set(v) = sp.edit { putFloat("bufferSeconds", v.toFloat()) }

	/** Desktop internal_player_max_buffer_seconds: ceiling for the +1 s per stall. */
	var maxBufferSeconds: Double
		get() = sp.getFloat("maxBufferSeconds", 18f).toDouble()
		set(v) = sp.edit { putFloat("maxBufferSeconds", v.toFloat()) }

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

	/** Minutes a scheduled recording starts early and runs late (desktop: 0 and 2). */
	var prePadMinutes: Int
		get() = sp.getInt("prePad", 0)
		set(v) = sp.edit { putInt("prePad", v) }

	var postPadMinutes: Int
		get() = sp.getInt("postPad", 2)
		set(v) = sp.edit { putInt("postPad", v) }

	/** Desktop "Automatic Announcements": 0 none, 1 errors only, 2 important events, 3 detailed status. */
	var announceLevel: Int
		get() = sp.getInt("announceLevel", 2)
		set(v) = sp.edit { putInt("announceLevel", v) }

	/** Subtitle text is spoken as it appears (a polite live region), like the desktop's subtitle speech. */
	var speakSubtitles: Boolean
		get() = sp.getBoolean("speakSubtitles", true)
		set(v) = sp.edit { putBoolean("speakSubtitles", v) }

	/** Live channels played, newest first; [0] is the one on now, [1] the previous channel. */
	var recent: List<String>
		get() = JSONArray(sp.getString("recent", "[]")).let { a -> List(a.length()) { a.getString(it) } }
		set(v) = sp.edit { putString("recent", JSONArray(v).toString()) }

	fun addRecent(key: String) {
		recent = (listOf(key) + recent.filter { it != key }).take(20)
	}

	/** Every setting, source and favourite as JSON, for Export settings. Typed so Import restores ints as ints. */
	fun export(): JSONObject {
		val out = JSONObject()
		for ((k, v) in sp.all) out.put(k, when (v) {
			is String -> JSONObject().put("s", v)
			is Boolean -> JSONObject().put("b", v)
			is Int -> JSONObject().put("i", v)
			is Long -> JSONObject().put("l", v)
			is Float -> JSONObject().put("f", v.toDouble())
			is Set<*> -> JSONObject().put("set", JSONArray(v.toList()))
			else -> continue
		})
		return JSONObject().put("app", "AccessibleIPTV-Android").put("settings", out)
	}

	/** Replaces every setting with an [export]. Throws on a file that is not one. */
	fun import(o: JSONObject) {
		require(o.optString("app") == "AccessibleIPTV-Android") { "Not a settings file" }
		val all = o.getJSONObject("settings")
		sp.edit {
			clear()
			for (k in all.keys()) {
				val v = all.getJSONObject(k)
				when {
					v.has("s") -> putString(k, v.getString("s"))
					v.has("b") -> putBoolean(k, v.getBoolean("b"))
					v.has("i") -> putInt(k, v.getInt("i"))
					v.has("l") -> putLong(k, v.getLong("l"))
					v.has("f") -> putFloat(k, v.getDouble("f").toFloat())
					v.has("set") -> v.getJSONArray("set").let { a -> putStringSet(k, (0 until a.length()).mapTo(HashSet()) { a.getString(it) }) }
				}
			}
		}
	}

	private val tracks get() = JSONObject(sp.getString("channelTracks", "{}")!!)

	/** Track name picked by hand on a channel; comes back ahead of every other rule. */
	fun rememberedTrack(channelKey: String): String? = tracks.optString(channelKey).ifEmpty { null }

	fun rememberTrack(channelKey: String, name: String) =
		sp.edit { putString("channelTracks", tracks.put(channelKey, name).toString()) }
}

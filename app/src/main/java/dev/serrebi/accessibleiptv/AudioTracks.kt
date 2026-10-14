package dev.serrebi.accessibleiptv

/** Audio-track preference matching, ported from the desktop client's audio_tracks.py. */
object AudioTracks {
	/** Wording providers use for audio description. Multilingual on purpose. */
	val AD_KEYWORDS = listOf(
		"audio description", "audiodescription", "audio descriptive", "descriptive audio",
		"described video", "description", "descriptive", "described", "audiodeskription",
		"hoerfilm", "hörfilm", "audiodeskrypcja", "audiovision", "descripcion", "descripción",
		"descrizione", "descrição", "dvs", "ad", "visual impaired", "visually impaired",
	)

	/** Below this length a keyword must match whole words: "ad" never fires on "Radio". */
	private const val COMPOUND_MIN = 6

	fun tokens(text: String?): List<String> =
		text.orEmpty().lowercase().split(nonWord).filter { it.isNotEmpty() }

	// Compiled once: each Regex holds native ICU memory until a GC finalizes it.
	private val nonWord = Regex("[\\W_]+")

	fun matches(name: String?, keyword: String?): Boolean {
		val have = tokens(name)
		val want = tokens(keyword)
		if (have.isEmpty() || want.isEmpty()) return false
		for (i in 0..have.size - want.size) if (have.subList(i, i + want.size) == want) return true
		val joined = want.joinToString("")
		return joined.length >= COMPOUND_MIN && joined in have.joinToString("")
	}

	/**
	 * Index of the track to select, or -1 to leave the player's default.
	 * Order: the per-channel remembered name, the audio-description flag/keywords,
	 * then the user's own keywords, most wanted first.
	 */
	fun choose(
		names: List<String>,
		describesVideo: List<Boolean>,
		remembered: String?,
		preferAd: Boolean,
		keywords: List<String>,
	): Int {
		if (!remembered.isNullOrBlank()) {
			names.indexOfFirst { it.equals(remembered, true) }.let { if (it >= 0) return it }
		}
		if (preferAd) {
			describesVideo.indexOf(true).let { if (it >= 0) return it }
			names.indexOfFirst { n -> AD_KEYWORDS.any { matches(n, it) } }.let { if (it >= 0) return it }
		}
		for (k in keywords) names.indexOfFirst { matches(it, k) }.let { if (it >= 0) return it }
		return -1
	}
}

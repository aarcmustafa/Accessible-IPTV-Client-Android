package dev.serrebi.accessibleiptv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as M3Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.framework.CastContext

object Player {
	/** Plays any list entry: shows open their episode list; Stalker links are fetched first. */
	fun open(a: AppCompatActivity, ch: Channel, status: TextView? = null, external: Boolean = false) {
		if (ch.kind == Channel.KIND_SERIES) return a.startActivity(EpisodesActivity.intent(a, ch))
		// Recently watched / Previous channel: live channels only, as on the desktop.
		if (ch.isLive) App.of(a).prefs.addRecent(ch.key)
		if (!ch.needsResolve) return if (external) openExternal(a, ch.url, ch.name) else play(a, ch.url, ch.name, ch.key)
		status?.status(a.getString(R.string.connecting))
		a.lifecycleScope.launch {
			val url = withContext(Dispatchers.IO) { runCatching { App.of(a).resolveUrl(ch) } }
			url.onSuccess { if (external) openExternal(a, it, ch.name) else play(a, it, ch.name, ch.key) }
				.onFailure { status?.status(a.getString(R.string.play_failed, it.message ?: "")) ?: a.alert(ch.name, it.message ?: "") }
		}
	}

	fun play(c: Context, url: String, title: String, channelKey: String, catchup: Boolean = false) {
		if (App.of(c).prefs.useExternalPlayer) return openExternal(c, url, title)
		c.startActivity(
			Intent(c, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title)
				.putExtra("key", channelKey).putExtra("catchup", catchup),
		)
	}

	fun openExternal(c: Context, url: String, title: String) {
		val plain = M3uParser.splitHeaders(url).first
		val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(plain), "video/*").putExtra("title", title)
		try {
			c.startActivity(Intent.createChooser(i, c.getString(R.string.play_external)))
		} catch (_: ActivityNotFoundException) {
			(c as? Activity)?.alert(c.getString(R.string.play_external), c.getString(R.string.no_external_player))
		}
	}
}

class PlayerActivity : AppCompatActivity() {
	private val app by lazy { App.of(this) }
	private lateinit var exo: ExoPlayer

	/** The CastPlayer wrapper when Cast is available: it moves playback to and from the TV by itself. */
	private lateinit var player: M3Player
	private var castContext: CastContext? = null
	private lateinit var status: TextView
	private lateinit var pause: Button
	private var channelKey = ""
	private var autoTrackDone = false
	private lateinit var cushion: Cushion
	private var hasPlayed = false
	private lateinit var castButton: Button
	private lateinit var video: PlayerView
	private var localFile = false
	private var fullscreen = false
	/** A live channel (not catch-up, a file or VOD): drops are reconnected like the desktop. */
	private var live = false
	private val reconnect = Reconnect(android.os.SystemClock::elapsedRealtime)
	private val retry = android.os.Handler(android.os.Looper.getMainLooper())

	/**
	 * Quick start, then build the cushion: a live stream that started on 2 s plays 5% slow
	 * until the full cushion is buffered (Cushion.speedFor), checked once a second.
	 */
	private val pace: Runnable = object : Runnable {
		override fun run() {
			if (exo.isPlaying && player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
				val want = cushion.speedFor(exo.totalBufferedDuration * 1000, exo.playbackParameters.speed)
				if (want != exo.playbackParameters.speed) exo.setPlaybackSpeed(want)
			}
			status.postDelayed(this, 1_000)
		}
	}

	/** Debug builds: buffer depth every 2 s, to tell a slow feed (drains bit by bit) from a delivery gap (drains at once). */
	private val heartbeat: Runnable = object : Runnable {
		override fun run() {
			android.util.Log.i("AiptvPlayer", "beat state=${exo.playbackState} playing=${exo.isPlaying} bufferedMs=${exo.totalBufferedDuration} cushionMs=${cushion.targetUs / 1000} speed=${exo.playbackParameters.speed}")
			retry.postDelayed(this, 2_000)
		}
	}
	private var pausedByStop = false

	// Back in full screen brings the controls back instead of closing the player.
	private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = setFullscreen(false)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_player)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		val url = intent.getStringExtra("url").orEmpty()
		val name = intent.getStringExtra("title").orEmpty()
		channelKey = intent.getStringExtra("key").orEmpty()
		val catchup = intent.getBooleanExtra("catchup", false)
		title = getString(if (catchup) R.string.catchup_title else R.string.player_title, name)
		status = findViewById(R.id.playerStatus)
		pause = findViewById(R.id.btnPause)

		val ch = app.channel(channelKey)
		live = !catchup && ch?.isLive == true
		channelName = name
		subtitles = findViewById(R.id.subtitles)
		showProgramme(announce = false)

		val (plain, headers) = M3uParser.splitHeaders(url)
		val http = DefaultHttpDataSource.Factory()
			.setUserAgent(headers["User-Agent"] ?: Net.USER_AGENT)
			.setDefaultRequestProperties(headers - "User-Agent")
			.setAllowCrossProtocolRedirects(true)
			// Some servers (Dispatcharr) take 20 s to send the first byte of a channel.
			.setConnectTimeoutMs(30_000).setReadTimeoutMs(45_000)
		// DefaultDataSource also opens content:// URIs, so saved recordings play here too.
		// Re-encoded IPTV TS often has no H.264 access-unit delimiters; without DETECT_ACCESS_UNITS
		// ExoPlayer cannot frame the video and plays sound only. NON_IDR_KEYFRAMES starts the
		// picture at the first usable frame instead of waiting for an IDR.
		val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
		)
		// Same cache as the desktop app: same seconds, same +1 s per stall, same maximum.
		cushion = Cushion(app.prefs.bufferSeconds, app.prefs.maxBufferSeconds)
		exo = ExoPlayer.Builder(this)
			.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors))
			.setLoadControl(cushion)
			.build()
		localFile = plain.startsWith("content://")
		// Devices without Google Play services (many TVs) have no Cast framework: play locally only.
		castContext = if (localFile) null else runCatching { CastContext.getSharedInstance(this) }.getOrNull()
		player = castContext?.let { runCatching { CastPlayer.Builder(this).setLocalPlayer(exo).build() }.getOrNull() } ?: exo
		video = findViewById(R.id.video)
		video.player = exo
		video.setOnClickListener { if (fullscreen) setFullscreen(false) }
		onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)
		// Cast receivers need a MIME type; TS is what a typeless IPTV stream almost always is.
		val item = MediaItem.Builder().setUri(plain).setMimeType(mimeOf(plain)).build()
		player.setMediaItem(item)
		player.addListener(listener)
		player.prepare()
		player.playWhenReady = true
		if (BuildConfig.DEBUG) heartbeat.run()
		if (live) status.post(pace)
		say(getString(R.string.connecting), 2)

		pause.setOnClickListener {
			if (player.isPlaying) player.pause()
			else {
				if (player.playbackState == M3Player.STATE_IDLE || player.playbackState == M3Player.STATE_ENDED) {
					// Retry after a failure: a live stream starts again at the live edge.
					retry.removeCallbacksAndMessages(null)
					reconnect.reset()
					if (live) player.seekToDefaultPosition()
					player.prepare()
				}
				player.play()
			}
		}
		castButton = findViewById(R.id.btnCast)
		castButton.setOnClickListener { cast() }
		findViewById<Button>(R.id.btnAudio).setOnClickListener { chooseAudio() }
		findViewById<Button>(R.id.btnSubs).setOnClickListener { chooseSubtitles() }
		// Our own subtitle line replaces PlayerView's, so the text is shown once and can be spoken.
		video.subtitleView?.visibility = View.GONE
		findViewById<Button>(R.id.btnRecord).setOnClickListener {
			val c = app.channel(channelKey)
			if (c == null || catchup) say(getString(R.string.cannot_record_this), 1)
			else { Recorder.start(this, c, app.epg.nowNext(c).now?.title.orEmpty()); say(getString(R.string.recording_channel, c.name), 2) }
		}
		findViewById<Button>(R.id.btnFull).setOnClickListener { setFullscreen(true) }
		findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
		// After layout: inside the scrolling button bar an early request is dropped and nothing has focus.
		pause.post { pause.requestFocus() }
	}

	private val listener = object : M3Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			pause.setText(if (isPlaying) R.string.pause else R.string.play)
			if (isPlaying) say(getString(R.string.playing), 3)
		}

		override fun onPlaybackStateChanged(state: Int) {
			// One line per change: lets stalls be measured from logcat (tag AiptvPlayer).
			android.util.Log.i("AiptvPlayer", "state=$state cushionMs=${cushion.targetUs / 1000} bufferedMs=${exo.totalBufferedDuration}")
			if (state == M3Player.STATE_READY) hasPlayed = true
			// A live stream that ends was dropped by the server: reconnect, as the desktop does.
			if (state == M3Player.STATE_ENDED && tryReconnect()) return
			if (state == M3Player.STATE_BUFFERING) {
				// Buffering after playback began is a stall: grow the cushion like the desktop.
				if (hasPlayed) cushion.onStall()
				say(getString(R.string.buffering), 3)
			}
			if (state == M3Player.STATE_ENDED) say(getString(R.string.stream_ended), 1)
		}

		override fun onPlayerError(error: PlaybackException) {
			if (tryReconnect()) return
			say(getString(R.string.play_failed, error.errorCodeName), 1)
			pause.setText(R.string.retry)
		}

		override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
			val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
			castButton.setText(if (remote) R.string.stop_casting else R.string.cast)
			say(
				if (remote) getString(R.string.casting_to, castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName.orEmpty())
				else getString(R.string.cast_stopped),
				2,
			)
		}

		override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
			val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
			subtitles.accessibilityLiveRegion = if (app.prefs.speakSubtitles) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
			// An empty cue clears the line without a second announcement.
			if (text.isNotEmpty()) subtitles.text = text
			subtitles.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (autoTrackDone) return
			val audio = audioTracks(tracks)
			if (audio.isEmpty()) return
			autoTrackDone = true
			val idx = AudioTracks.choose(
				audio.map { it.name },
				audio.map { it.describesVideo },
				app.prefs.rememberedTrack(channelKey),
				app.prefs.preferAudioDescription,
				app.prefs.audioKeywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
			)
			if (idx >= 0 && !audio[idx].selected) select(audio[idx])
		}
	}

	/**
	 * A status line, spoken only up to the "Automatic announcements" level in Settings
	 * (desktop: 1 errors, 2 important events, 3 detailed status). The text always updates.
	 */
	private fun say(text: String, priority: Int) {
		status.accessibilityLiveRegion = if (app.prefs.announceLevel >= priority) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
		status.text = text
	}

	private var channelName = ""
	private lateinit var subtitles: TextView
	private val programmeTick = Runnable { showProgramme(announce = true) }

	/**
	 * Shows what is on and, when the guide says the programme changed, says "Now: ..." as an
	 * important event. Re-arms itself for the end of the programme. Live channels only.
	 */
	private fun showProgramme(announce: Boolean) {
		val nowPlaying = findViewById<TextView>(R.id.nowPlaying)
		val ch = app.channel(channelKey)?.takeIf { live }
		val nn = ch?.let { app.epg.nowNext(it) }
		val on = nn?.now
		nowPlaying.text = on?.let { Format.sentences(listOf(channelName, getString(R.string.now_programme, Format.programme(it)))) } ?: channelName
		if (announce && on != null) say(getString(R.string.now_programme, Format.programme(on)), 2)
		nowPlaying.removeCallbacks(programmeTick)
		val change = on?.stop ?: nn?.next?.start ?: return
		nowPlaying.postDelayed(programmeTick, maxOf(1_000, change - System.currentTimeMillis() + 2_000))
	}

	private class TextTrack(val group: Tracks.Group, val index: Int, val name: String)

	private fun textTracks(): List<TextTrack> = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
		(0 until g.length).map { i ->
			val f = g.getTrackFormat(i)
			val name = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } })
				.distinct().joinToString(", ").ifEmpty { getString(R.string.track_n, i + 1) }
			TextTrack(g, i, name)
		}
	}

	/** Off, or one subtitle track; the chosen text is shown under the video and spoken. */
	private fun chooseSubtitles() {
		val tracks = textTracks()
		if (tracks.isEmpty()) return say(getString(R.string.no_subtitles), 1)
		val off = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
		val current = if (off) 0 else tracks.indexOfFirst { it.group.isTrackSelected(it.index) } + 1
		val names = listOf(getString(R.string.subtitles_off)) + tracks.map { it.name }
		AlertDialog.Builder(this).setTitle(R.string.subtitles)
			.setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
				val p = exo.trackSelectionParameters.buildUpon()
				if (which == 0) {
					p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
					subtitles.visibility = View.GONE
				} else {
					val t = tracks[which - 1]
					p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
				}
				exo.trackSelectionParameters = p.build()
				say(getString(R.string.subtitles_set, names[which]), 2)
				d.dismiss()
			}.show()
	}

	/** Schedules the next reconnect of a live stream; false when this stream does not get one or the budget is spent. */
	private fun tryReconnect(): Boolean {
		if (!live || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return false
		val delay = reconnect.next() ?: return false
		say(getString(R.string.reconnecting), 2)
		retry.postDelayed({
			// Behind the live window after a drop: start again at the live edge.
			player.seekToDefaultPosition()
			player.prepare()
			player.play()
		}, delay)
		return true
	}

	private fun mimeOf(url: String): String {
		val path = url.substringBefore('?').lowercase()
		return when {
			path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
			path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
			path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
			else -> MimeTypes.VIDEO_MP2T
		}
	}

	/**
	 * Full screen hides every control and the system bars. The video becomes one focusable,
	 * labelled item so a screen reader, a remote's OK button or a tap can bring the controls back.
	 */
	private fun setFullscreen(on: Boolean) {
		fullscreen = on
		exitFullscreenOnBack.isEnabled = on
		val shown = if (on) View.GONE else View.VISIBLE
		val bars = WindowCompat.getInsetsController(window, window.decorView)
		if (on) {
			bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			bars.hide(WindowInsetsCompat.Type.systemBars())
		} else bars.show(WindowInsetsCompat.Type.systemBars())
		// Order matters: the new target must hold focus before the old one stops being focusable,
		// or Android hands focus to the first button (Pause) and the next OK pauses the stream.
		if (on) {
			video.isFocusable = true
			video.isClickable = true
			video.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
			video.contentDescription = getString(R.string.video_full_screen)
			video.requestFocus()
			for (id in listOf(R.id.controls, R.id.nowPlaying, R.id.playerStatus)) findViewById<View>(id).visibility = shown
		} else {
			for (id in listOf(R.id.controls, R.id.nowPlaying, R.id.playerStatus)) findViewById<View>(id).visibility = shown
			findViewById<View>(R.id.btnFull).requestFocus()
			video.isFocusable = false
			video.isClickable = false
			video.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			video.contentDescription = null
		}
		val target = if (on) video else findViewById<View>(R.id.btnFull)
		target.post { target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
	}


	private fun cast() {
		if (localFile) return say(getString(R.string.cast_local_file), 1)
		val ctx = castContext ?: return say(getString(R.string.cast_unavailable), 1)
		if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) ctx.sessionManager.endCurrentSession(true)
		else MediaRouteChooserDialog(this).apply { routeSelector = ctx.mergedSelector ?: MediaRouteSelector.EMPTY }.show()
	}

	private class AudioTrack(val group: Tracks.Group, val index: Int, val name: String, val describesVideo: Boolean, val selected: Boolean)

	private fun audioTracks(tracks: Tracks = exo.currentTracks): List<AudioTrack> {
		val out = ArrayList<AudioTrack>()
		for (g in tracks.groups) {
			if (g.type != C.TRACK_TYPE_AUDIO) continue
			for (i in 0 until g.length) {
				val f = g.getTrackFormat(i)
				val name = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } })
					.distinct().joinToString(", ").ifEmpty { getString(R.string.track_n, out.size + 1) }
				out += AudioTrack(g, i, name, f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0, g.isTrackSelected(i))
			}
		}
		return out
	}

	private fun select(t: AudioTrack) {
		exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index)).build()
	}

	private fun chooseAudio() {
		val audio = audioTracks()
		if (audio.isEmpty()) { say(getString(R.string.no_audio_tracks), 1); return }
		val current = audio.indexOfFirst { it.selected }
		AlertDialog.Builder(this).setTitle(R.string.audio_track)
			.setSingleChoiceItems(audio.map { it.name }.toTypedArray(), current) { d, which ->
				select(audio[which])
				// Remembered per channel, ahead of every other rule, like the desktop app.
				if (channelKey.isNotEmpty()) app.prefs.rememberTrack(channelKey, audio[which].name)
				say(getString(R.string.audio_track_set, audio[which].name), 2)
				d.dismiss()
			}.show()
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
		// Media keys on remotes and keyboards.
		KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { pause.performClick(); true }
		KeyEvent.KEYCODE_A -> { chooseAudio(); true }
		KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_CAPTIONS -> { chooseSubtitles(); true }
		KeyEvent.KEYCODE_F -> { setFullscreen(!fullscreen); true }
		else -> super.onKeyUp(keyCode, event)
	}

	override fun onStop() {
		super.onStop()
		// Leaving the app stops local playback; a cast session keeps playing on the TV.
		if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE && player.playWhenReady) {
			player.pause()
			pausedByStop = true
		}
	}

	override fun onStart() {
		super.onStart()
		// Back in the app: live TV picks up at the live edge, not minutes behind where it stopped.
		if (!pausedByStop) return
		pausedByStop = false
		if (live) player.seekToDefaultPosition()
		player.play()
	}

	override fun onDestroy() {
		retry.removeCallbacksAndMessages(null)
		findViewById<View>(R.id.nowPlaying).removeCallbacks(programmeTick)
		status.removeCallbacks(pace)
		player.release() // releases the wrapped local player too
		super.onDestroy()
	}
}

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
		findViewById<TextView>(R.id.nowPlaying).text = if (catchup || ch == null) name
		else app.epg.nowNext(ch).firstOrNull()?.let { Format.sentences(listOf(name, getString(R.string.now_programme, Format.programme(it)))) } ?: name

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
		status.status(getString(R.string.connecting))

		pause.setOnClickListener { if (player.isPlaying) player.pause() else { if (player.playbackState == M3Player.STATE_IDLE) player.prepare(); player.play() } }
		castButton = findViewById(R.id.btnCast)
		castButton.setOnClickListener { cast() }
		findViewById<Button>(R.id.btnAudio).setOnClickListener { chooseAudio() }
		findViewById<Button>(R.id.btnRecord).setOnClickListener {
			val c = app.channel(channelKey)
			if (c == null || catchup) status.status(getString(R.string.cannot_record_this))
			else { Recorder.start(this, c, app.epg.nowNext(c).firstOrNull()?.title.orEmpty()); status.status(getString(R.string.recording_channel, c.name)) }
		}
		findViewById<Button>(R.id.btnFull).setOnClickListener { setFullscreen(true) }
		findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
		pause.requestFocus()
	}

	private val listener = object : M3Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			pause.setText(if (isPlaying) R.string.pause else R.string.play)
			if (isPlaying) status.status(getString(R.string.playing))
		}

		override fun onPlaybackStateChanged(state: Int) {
			// One line per change: lets stalls be measured from logcat (tag AiptvPlayer).
			android.util.Log.i("AiptvPlayer", "state=$state cushionMs=${cushion.targetUs / 1000}")
			if (state == M3Player.STATE_READY) hasPlayed = true
			if (state == M3Player.STATE_BUFFERING) {
				// Buffering after playback began is a stall: grow the cushion like the desktop.
				if (hasPlayed) cushion.onStall()
				status.status(getString(R.string.buffering))
			}
			if (state == M3Player.STATE_ENDED) status.status(getString(R.string.stream_ended))
		}

		override fun onPlayerError(error: PlaybackException) {
			status.status(getString(R.string.play_failed, error.errorCodeName))
			pause.setText(R.string.retry)
		}

		override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
			val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
			castButton.setText(if (remote) R.string.stop_casting else R.string.cast)
			status.status(
				if (remote) getString(R.string.casting_to, castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName.orEmpty())
				else getString(R.string.cast_stopped),
			)
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
		if (localFile) return status.status(getString(R.string.cast_local_file))
		val ctx = castContext ?: return status.status(getString(R.string.cast_unavailable))
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
		if (audio.isEmpty()) { status.status(getString(R.string.no_audio_tracks)); return }
		val current = audio.indexOfFirst { it.selected }
		AlertDialog.Builder(this).setTitle(R.string.audio_track)
			.setSingleChoiceItems(audio.map { it.name }.toTypedArray(), current) { d, which ->
				select(audio[which])
				// Remembered per channel, ahead of every other rule, like the desktop app.
				if (channelKey.isNotEmpty()) app.prefs.rememberTrack(channelKey, audio[which].name)
				status.status(getString(R.string.audio_track_set, audio[which].name))
				d.dismiss()
			}.show()
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
		// Media keys on remotes and keyboards.
		KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { pause.performClick(); true }
		KeyEvent.KEYCODE_A -> { chooseAudio(); true }
		KeyEvent.KEYCODE_F -> { setFullscreen(!fullscreen); true }
		else -> super.onKeyUp(keyCode, event)
	}

	override fun onStop() {
		super.onStop()
		// Leaving the app stops local playback; a cast session keeps playing on the TV.
		if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) player.pause()
	}

	override fun onDestroy() {
		player.release() // releases the wrapped local player too
		super.onDestroy()
	}
}

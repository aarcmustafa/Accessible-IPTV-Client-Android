package dev.serrebi.accessibleiptv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Recording: a foreground service copies the live stream into Movies/AccessibleIPTV. */
object Recorder {
	const val NOTIF_CHANNEL = "recordings"
	const val FOLDER = "Movies/AccessibleIPTV"

	data class Scheduled(val id: Int, val channelKey: String, val name: String, val title: String, val start: Long, val end: Long) {
		fun toJson() = JSONObject().put("id", id).put("ch", channelKey).put("n", name).put("t", title).put("s", start).put("e", end)
		companion object {
			fun fromJson(o: JSONObject) = Scheduled(o.getInt("id"), o.getString("ch"), o.getString("n"), o.optString("t"), o.getLong("s"), o.getLong("e"))
		}
	}

	/** A recording or download running now. [channelKey] is "" for downloads. */
	data class Active(val key: String, val label: String, val channelKey: String, val started: Long, val end: Long)

	/** What RecordService is writing right now, for the Recordings screen and the channel menus. */
	val active = ConcurrentHashMap<String, Active>()

	fun isRecording(channelKey: String) = active.containsKey(channelKey)

	fun createChannel(c: Context) {
		c.getSystemService(NotificationManager::class.java).createNotificationChannel(
			NotificationChannel(NOTIF_CHANNEL, c.getString(R.string.recordings), NotificationManager.IMPORTANCE_LOW),
		)
	}

	/** Starts recording now. [endMs] 0 = until stopped. */
	fun start(c: Context, ch: Channel, title: String, endMs: Long = 0) {
		val i = Intent(c, RecordService::class.java)
			.putExtra("key", ch.key).putExtra("url", ch.url).putExtra("name", ch.name)
			.putExtra("title", title).putExtra("end", endMs)
		askNotifications(c)
		c.startForegroundService(i)
	}

	/** Saves a catch-up programme or any finite URL to a file (catch-up downloads). */
	fun download(c: Context, url: String, name: String, title: String, expectMs: Long = 0) {
		askNotifications(c)
		c.startForegroundService(
			Intent(c, RecordService::class.java)
				.putExtra("key", "dl:" + url.hashCode()).putExtra("url", url).putExtra("name", name)
				.putExtra("title", title).putExtra("end", 0L).putExtra("expect", expectMs),
		)
	}

	/** Stops one recording or download; what was written is kept. */
	fun stop(c: Context, key: String) =
		c.startService(Intent(c, RecordService::class.java).setAction(RecordService.ACTION_STOP_ONE).putExtra("key", key))

	/**
	 * Desktop _catchup_download_is_truncated: some providers close a catch-up file part-way
	 * and the copy looks finished. Short by more than 10% of the programme = ended early.
	 */
	fun endedEarly(writtenMs: Long?, expectMs: Long) = expectMs > 0 && writtenMs != null && writtenMs < expectMs * 9 / 10

	/**
	 * Copies [open]'s stream into [out] until [done]. With [reconnect], a dropped or ended
	 * stream is opened again after [pauseMs] and appended to the same file; [maxMisses]
	 * tries in a row without a byte end it. True when anything was written.
	 */
	fun copy(
		open: () -> java.io.InputStream,
		out: java.io.OutputStream,
		reconnect: Boolean,
		pauseMs: Long = 2_000,
		maxMisses: Int = 10,
		done: () -> Boolean,
	): Boolean {
		var wrote = false
		var misses = 0
		val buf = ByteArray(256 * 1024)
		while (!done()) {
			var got = false
			try {
				open().use { input ->
					while (!done()) {
						val n = input.read(buf)
						if (n < 0) break
						out.write(buf, 0, n)
						wrote = true
						got = true
					}
				}
			} catch (e: java.io.IOException) {
				// A dropped connection: below, the same as the server ending the stream.
			}
			if (!reconnect || done()) break
			misses = if (got) 0 else misses + 1
			if (misses >= maxMisses) break
			try { Thread.sleep(pauseMs) } catch (_: InterruptedException) { break }
		}
		return wrote
	}

	/** Android 13+: without this, a recording's notification, its Stop button and the "Saved" notice never show. */
	private fun askNotifications(c: Context) {
		if (Build.VERSION.SDK_INT < 33 || c !is android.app.Activity) return
		if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
			c.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
		}
	}

	fun stopAll(c: Context) = c.startService(Intent(c, RecordService::class.java).setAction(RecordService.ACTION_STOP))

	private fun sp(c: Context) = c.getSharedPreferences("recordings", Context.MODE_PRIVATE)

	fun scheduled(c: Context): List<Scheduled> =
		JSONArray(sp(c).getString("list", "[]")).let { a -> List(a.length()) { Scheduled.fromJson(a.getJSONObject(it)) } }
			.filter { it.end > System.currentTimeMillis() }.sortedBy { it.start }

	private fun save(c: Context, list: List<Scheduled>) = sp(c).edit { putString("list", JSONArray(list.map { it.toJson() }).toString()) }

	fun canScheduleExact(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

	/** Schedules [start, end] widened by the padding in Settings (desktop: 0 minutes before, 2 after). */
	fun schedule(c: Context, ch: Channel, title: String, start: Long, end: Long) {
		val prefs = App.of(c).prefs
		val s = Scheduled(
			(System.currentTimeMillis() % Int.MAX_VALUE).toInt(), ch.key, ch.name, title,
			start - prefs.prePadMinutes * 60_000L, end + prefs.postPadMinutes * 60_000L,
		)
		save(c, scheduled(c) + s)
		arm(c, s)
		askNotifications(c)
	}

	private fun arm(c: Context, s: Scheduled) {
		val am = c.getSystemService(AlarmManager::class.java)
		val at = maxOf(System.currentTimeMillis() + 1000, s.start - 60_000) // a minute early: streams can be slow to start.
		if (canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c, s.id))
		else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c, s.id))
	}

	/** Alarms do not survive a reboot; this puts them back. */
	class BootReceiver : BroadcastReceiver() {
		override fun onReceive(c: Context, intent: Intent) {
			if (intent.action == Intent.ACTION_BOOT_COMPLETED) scheduled(c).forEach { arm(c, it) }
		}
	}

	/** A scheduled recording that could not start says so, instead of silently not existing. */
	private fun failed(c: Context, name: String) {
		runCatching {
			c.getSystemService(NotificationManager::class.java).notify(
				name.hashCode(),
				NotificationCompat.Builder(c, NOTIF_CHANNEL).setSmallIcon(android.R.drawable.stat_notify_error)
					.setContentTitle(c.getString(R.string.recording_failed, name)).build(),
			)
		}
	}

	fun cancel(c: Context, id: Int) {
		save(c, scheduled(c).filter { it.id != id })
		c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c, id))
	}

	private fun alarmIntent(c: Context, id: Int) = PendingIntent.getBroadcast(
		c, id, Intent(c, AlarmReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
	)

	class AlarmReceiver : BroadcastReceiver() {
		override fun onReceive(c: Context, intent: Intent) {
			val s = scheduled(c).firstOrNull { it.id == intent.getIntExtra("id", -1) } ?: return
			save(c, scheduled(c).filter { it.id != s.id })
			val app = App.of(c)
			// A cold process has to read the channel cache first; that is too slow for onReceive.
			val pending = goAsync()
			Thread {
				try {
					val ch = app.channel(s.channelKey) ?: run {
						kotlinx.coroutines.runBlocking { app.load(App.LIVE, false) }
						app.channel(s.channelKey)
					}
					if (ch != null) start(c, ch, s.title, s.end) else failed(c, s.name)
				} catch (e: Exception) {
					// Android refused the background start (no exact-alarm exemption) or the playlist could not load.
					failed(c, s.name)
				} finally {
					pending.finish()
				}
			}.start()
		}
	}
}

class RecordService : Service() {
	private val jobs = ConcurrentHashMap<String, Thread>()
	private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
	@Volatile private var stopping = false

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			stopping = true
			jobs.values.forEach { it.interrupt() }
			stopSelf()
			return START_NOT_STICKY
		}
		if (intent?.action == ACTION_STOP_ONE) {
			intent.getStringExtra("key")?.let { k -> cancelled += k; jobs[k]?.interrupt() }
			if (jobs.isEmpty()) stopSelf()
			return START_NOT_STICKY
		}
		val key = intent?.getStringExtra("key") ?: return START_NOT_STICKY
		val name = intent.getStringExtra("name").orEmpty()
		ServiceCompat.startForeground(
			this, 1, notification(getString(R.string.recording_channel, name)),
			// specialUse, not dataSync: Android 15 stops dataSync after 6 hours a day, which ends an evening of recordings.
			if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
		)
		if (jobs.containsKey(key)) return START_NOT_STICKY
		val url = intent.getStringExtra("url").orEmpty()
		val title = intent.getStringExtra("title").orEmpty()
		val end = intent.getLongExtra("end", 0)
		val expect = intent.getLongExtra("expect", 0)
		cancelled -= key
		val label = listOf(name, title).filter { it.isNotBlank() }.joinToString(" - ")
		Recorder.active[key] = Recorder.Active(key, label, if (key.startsWith("dl:")) "" else key, System.currentTimeMillis(), end)
		jobs[key] = Thread { record(key, name, title, url, end, expect) }.also { it.start() }
		return START_NOT_STICKY
	}

	private fun record(key: String, name: String, title: String, url: String, end: Long, expect: Long) {
		val real = if (!url.startsWith(Channel.STALKER_SCHEME)) url else runCatching {
			val app = App.of(this)
			app.resolveUrl(app.channel(key)!!)
		}.getOrNull()
		if (real == null) {
			jobs.remove(key)
			Recorder.active.remove(key)
			if (jobs.isEmpty()) stopSelf()
			return
		}
		val (plain, headers) = M3uParser.splitHeaders(real)
		val label = listOf(name, title).filter { it.isNotBlank() }.joinToString(" - ")
		val fileName = "${label.replace(Regex("[\\\\/:*?\"<>|]"), "_")} ${Format.dateTime(System.currentTimeMillis()).replace(Regex("[\\\\/:]"), "-")}.ts"
		val values = ContentValues().apply {
			put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
			put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
			put(MediaStore.Video.Media.RELATIVE_PATH, Recorder.FOLDER)
			put(MediaStore.Video.Media.IS_PENDING, 1)
		}
		val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
		var ok = false
		var early = false
		val done = { stopping || key in cancelled || Thread.currentThread().isInterrupted || (end != 0L && System.currentTimeMillis() >= end) }
		try {
			// A catch-up download that ends early is fetched again from the start, up to three times.
			for (attempt in 1..3) {
				val clock = TsClock()
				if (uri != null) contentResolver.openOutputStream(uri, "wt")?.use { raw ->
					val out = clock.watching(raw)
					// Live recordings ride out drops until their end time; a download (a file) ends at its end.
					ok = Recorder.copy({ Net.open(plain, headers) }, out, reconnect = !key.startsWith("dl:"), done = done)
				}
				early = Recorder.endedEarly(clock.durationMs, expect)
				if (!early || done()) break
				try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
			}
		} catch (_: Exception) {
			// Storage failed or stop pressed: keep what was written.
		} finally {
			if (uri != null) {
				if (ok) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
				else contentResolver.delete(uri, null, null)
			}
			jobs.remove(key)
			Recorder.active.remove(key)
			cancelled -= key
			val msg = when { !ok -> R.string.recording_failed; early -> R.string.download_ended_early; else -> R.string.recording_saved }
			getSystemService(NotificationManager::class.java).notify(
				key.hashCode(),
				NotificationCompat.Builder(this, Recorder.NOTIF_CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
					.setContentTitle(getString(msg, label)).build(),
			)
			if (jobs.isEmpty()) stopSelf()
		}
	}

	private fun notification(text: String): Notification {
		val stop = PendingIntent.getService(this, 0, Intent(this, RecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
		return NotificationCompat.Builder(this, Recorder.NOTIF_CHANNEL)
			.setSmallIcon(android.R.drawable.presence_video_online)
			.setContentTitle(text)
			.setOngoing(true)
			.addAction(0, getString(R.string.stop_recording), stop)
			.build()
	}

	companion object {
		const val ACTION_STOP = "stop"
		const val ACTION_STOP_ONE = "stopOne"
	}
}

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
		c.startForegroundService(i)
	}

	/** Saves a catch-up programme or any finite URL to a file (catch-up downloads). */
	fun download(c: Context, url: String, name: String, title: String) {
		c.startForegroundService(
			Intent(c, RecordService::class.java)
				.putExtra("key", "dl:" + url.hashCode()).putExtra("url", url).putExtra("name", name)
				.putExtra("title", title).putExtra("end", 0L),
		)
	}

	fun stopAll(c: Context) = c.startService(Intent(c, RecordService::class.java).setAction(RecordService.ACTION_STOP))

	private fun sp(c: Context) = c.getSharedPreferences("recordings", Context.MODE_PRIVATE)

	fun scheduled(c: Context): List<Scheduled> =
		JSONArray(sp(c).getString("list", "[]")).let { a -> List(a.length()) { Scheduled.fromJson(a.getJSONObject(it)) } }
			.filter { it.end > System.currentTimeMillis() }.sortedBy { it.start }

	private fun save(c: Context, list: List<Scheduled>) = sp(c).edit { putString("list", JSONArray(list.map { it.toJson() }).toString()) }

	fun canScheduleExact(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

	fun schedule(c: Context, ch: Channel, title: String, start: Long, end: Long) {
		val s = Scheduled((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), ch.key, ch.name, title, start, end)
		save(c, scheduled(c) + s)
		arm(c, s)
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
					if (ch != null) start(c, ch, s.title, s.end)
				} finally {
					pending.finish()
				}
			}.start()
		}
	}
}

class RecordService : Service() {
	private val jobs = ConcurrentHashMap<String, Thread>()
	@Volatile private var stopping = false

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			stopping = true
			jobs.values.forEach { it.interrupt() }
			stopSelf()
			return START_NOT_STICKY
		}
		val key = intent?.getStringExtra("key") ?: return START_NOT_STICKY
		val name = intent.getStringExtra("name").orEmpty()
		ServiceCompat.startForeground(
			this, 1, notification(getString(R.string.recording_channel, name)),
			if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
		)
		if (jobs.containsKey(key)) return START_NOT_STICKY
		val url = intent.getStringExtra("url").orEmpty()
		val title = intent.getStringExtra("title").orEmpty()
		val end = intent.getLongExtra("end", 0)
		jobs[key] = Thread { record(key, name, title, url, end) }.also { it.start() }
		return START_NOT_STICKY
	}

	private fun record(key: String, name: String, title: String, url: String, end: Long) {
		val real = if (!url.startsWith(Channel.STALKER_SCHEME)) url else runCatching {
			val app = App.of(this)
			app.resolveUrl(app.channel(key)!!)
		}.getOrNull()
		if (real == null) {
			jobs.remove(key)
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
		try {
			if (uri != null) contentResolver.openOutputStream(uri)?.use { out ->
				Net.open(plain, headers).use { input ->
					val buf = ByteArray(256 * 1024)
					while (!stopping && !Thread.currentThread().isInterrupted && (end == 0L || System.currentTimeMillis() < end)) {
						val n = input.read(buf)
						if (n < 0) break
						out.write(buf, 0, n)
						ok = true
					}
				}
			}
		} catch (_: Exception) {
			// Stream dropped or stop pressed: keep what was written.
		} finally {
			if (uri != null) {
				if (ok) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
				else contentResolver.delete(uri, null, null)
			}
			jobs.remove(key)
			getSystemService(NotificationManager::class.java).notify(
				key.hashCode(),
				NotificationCompat.Builder(this, Recorder.NOTIF_CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
					.setContentTitle(getString(if (ok) R.string.recording_saved else R.string.recording_failed, label)).build(),
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
	}
}

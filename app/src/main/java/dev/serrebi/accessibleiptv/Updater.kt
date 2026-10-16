package dev.serrebi.accessibleiptv

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Self-update from GitHub Releases: newest release with an .apk asset, SHA-256 checked
 * against the digest GitHub publishes, installed through PackageInstaller (Android always
 * asks the user to confirm). Updates must be signed with the same key as the installed app.
 */
object Updater {
	const val REPO = "serrebidev/Accessible-IPTV-Client-Android"

	data class Release(val version: String, val apkUrl: String, val sha256: String?, val notes: String)

	/** The newer release, or null when this build is current. Blocking. */
	fun check(currentVersion: String): Release? {
		val o = JSONObject(Net.text("https://api.github.com/repos/$REPO/releases/latest"))
		val version = o.optString("tag_name").removePrefix("v")
		if (!isNewer(version, currentVersion)) return null
		val assets = o.optJSONArray("assets") ?: return null
		for (i in 0 until assets.length()) {
			val a = assets.getJSONObject(i)
			if (a.optString("name").endsWith(".apk", true)) {
				val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
				return Release(version, a.getString("browser_download_url"), digest, o.optString("body").trim())
			}
		}
		return null
	}

	/** Numeric compare of dotted versions: "0.10.0" is newer than "0.9.3". */
	fun isNewer(candidate: String, current: String): Boolean {
		fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
		val a = parts(candidate)
		val b = parts(current)
		for (i in 0 until maxOf(a.size, b.size)) {
			val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
			if (d != 0) return d > 0
		}
		return false
	}

	/** Downloads the APK into the cache and verifies it. Blocking. */
	fun download(c: Context, r: Release): File {
		// A release without a published digest cannot be verified: refuse it rather than install blind.
		val want = r.sha256 ?: throw IOException("The release has no checksum")
		val f = File(c.cacheDir, "update.apk")
		val md = MessageDigest.getInstance("SHA-256")
		Net.open(r.apkUrl).use { input ->
			f.outputStream().use { out ->
				val buf = ByteArray(64 * 1024)
				while (true) {
					val n = input.read(buf)
					if (n < 0) break
					out.write(buf, 0, n)
					md.update(buf, 0, n)
				}
			}
		}
		val got = md.digest().joinToString("") { "%02x".format(it) }
		if (!got.equals(want, true)) {
			f.delete()
			throw IOException("Checksum mismatch")
		}
		return f
	}

	/** True when Android lets this app install packages; otherwise opens the setting for it. */
	fun ensureInstallAllowed(a: Activity): Boolean {
		if (a.packageManager.canRequestPackageInstalls()) return true
		a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
		return false
	}

	fun install(c: Context, apk: File) {
		val pi = c.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
		val id = pi.createSession(params)
		pi.openSession(id).use { s ->
			s.openWrite("update.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
			// The installer reports back here; MUTABLE because it adds the status extras.
			val cb = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
			s.commit(cb.intentSender)
		}
	}

	class InstallReceiver : BroadcastReceiver() {
		override fun onReceive(c: Context, intent: Intent) {
			if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
				val confirm = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
				c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		}
	}
}

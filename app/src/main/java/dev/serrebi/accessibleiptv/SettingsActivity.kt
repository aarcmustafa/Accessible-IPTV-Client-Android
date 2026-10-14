package dev.serrebi.accessibleiptv

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** Options. Saved when the screen is left: no Save button to hunt for. */
class SettingsActivity : AppCompatActivity() {
	private val prefs by lazy { App.of(this).prefs }
	private lateinit var preferAd: CheckBox
	private lateinit var keywords: EditText
	private lateinit var external: CheckBox
	private lateinit var showUrl: CheckBox
	private lateinit var days: EditText
	private lateinit var buffer: EditText
	private lateinit var maxBuffer: EditText

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settings)
		title = getString(R.string.settings)
		listOf(R.id.hAudio, R.id.hPlayback, R.id.hGuide).forEach { findViewById<View>(it).heading() }
		preferAd = findViewById(R.id.preferAd)
		keywords = findViewById(R.id.keywords)
		external = findViewById(R.id.external)
		days = findViewById(R.id.days)
		showUrl = findViewById(R.id.showUrl)
		showUrl.isChecked = prefs.showStreamUrl
		preferAd.isChecked = prefs.preferAudioDescription
		keywords.setText(prefs.audioKeywords)
		external.isChecked = prefs.useExternalPlayer
		days.setText(prefs.epgDaysAhead.toString())
		buffer = findViewById(R.id.buffer)
		maxBuffer = findViewById(R.id.maxBuffer)
		buffer.setText(plain(prefs.bufferSeconds))
		maxBuffer.setText(plain(prefs.maxBufferSeconds))
	}

	override fun onPause() {
		super.onPause()
		prefs.preferAudioDescription = preferAd.isChecked
		prefs.audioKeywords = keywords.text.toString().trim()
		prefs.useExternalPlayer = external.isChecked
		prefs.showStreamUrl = showUrl.isChecked
		val d = days.text.toString().toIntOrNull()?.coerceIn(1, 14) ?: 2
		if (d != prefs.epgDaysAhead) prefs.lastEpgImport = 0 // re-import with the new window
		prefs.epgDaysAhead = d
		// Desktop _apply_internal_player_bounds: max in (0, 300], base > 0 and never above max.
		val max = (maxBuffer.text.toString().toDoubleOrNull() ?: prefs.maxBufferSeconds).let { if (it <= 0) 18.0 else minOf(it, 300.0) }
		val base = (buffer.text.toString().toDoubleOrNull() ?: prefs.bufferSeconds).let { if (it <= 0) 6.0 else minOf(it, max) }
		prefs.maxBufferSeconds = max
		prefs.bufferSeconds = base
	}

	/** "6" rather than "6.0" in the field. */
	private fun plain(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}

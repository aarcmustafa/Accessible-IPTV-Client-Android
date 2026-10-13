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
	}
}

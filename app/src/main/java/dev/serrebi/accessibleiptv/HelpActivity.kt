package dev.serrebi.accessibleiptv

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Offline user guide. "# " lines become headings so TalkBack heading navigation works. */
class HelpActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.help)
		val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
		// A raw file, not a string resource: aapt folds a string's line breaks into spaces, which
		// turned the whole guide into one heading with the "#" marks read aloud.
		val guide = resources.openRawResource(R.raw.help).bufferedReader().use { it.readText() }
		for (block in guide.trim().split(Regex("\\n\\s*\\n"))) {
			val heading = block.startsWith("# ")
			col.addView(TextView(this).apply {
				text = block.removePrefix("# ").trim()
				// Body paragraphs are focusable so a remote's D-pad can scroll through them.
				isFocusable = true
				setPadding(0, if (heading) 24 else 8, 0, 8)
				if (heading) { setTextAppearance(android.R.style.TextAppearance_Large); heading() }
			})
		}
		setContentView(ScrollView(this).apply { addView(col) })
	}
}

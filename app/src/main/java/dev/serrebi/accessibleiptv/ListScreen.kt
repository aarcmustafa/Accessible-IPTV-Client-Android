package dev.serrebi.accessibleiptv

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Base for the list screens: heading, status line, optional filter and button bar. */
abstract class ListScreen : AppCompatActivity() {
	protected val app by lazy { App.of(this) }
	protected lateinit var status: TextView
	protected lateinit var heading: TextView
	protected lateinit var list: RecyclerView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_list)
		status = findViewById(R.id.status)
		heading = findViewById(R.id.listHeading)
		heading.heading()
		list = findViewById(R.id.list)
		list.layoutManager = LinearLayoutManager(this)
	}

	protected fun addButton(label: String, onClick: () -> Unit): Button {
		val bar = findViewById<LinearLayout>(R.id.buttons)
		bar.visibility = View.VISIBLE
		return Button(this, null, 0, R.style.BarButton).apply {
			text = label
			setOnClickListener { onClick() }
			bar.addView(this)
		}
	}

	protected fun enableFilter(onChange: (String) -> Unit) {
		findViewById<EditText>(R.id.filter).apply {
			visibility = View.VISIBLE
			addTextChangedListener(object : TextWatcher {
				override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
				override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
				override fun afterTextChanged(s: Editable?) = onChange(s.toString().trim())
			})
			onSearchKey(heading) { onChange(text.toString().trim()) }
		}
	}
}

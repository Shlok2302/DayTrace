package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.voicerecorder.R
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.GeminiSummarizer

/**
 * Settings > AI & Processing > Known terms.
 *
 * Names and words Gemini should spell correctly, on top of the built-in
 * list. Tap a term to remove it.
 */
class KnownTermsFragment : Fragment(R.layout.fragment_known_terms) {

    private lateinit var settings: AppSettings
    private lateinit var terms: FlowLayout
    private lateinit var input: EditText
    private lateinit var empty: TextView

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        settings = AppSettings(requireContext())
        terms = view.findViewById(R.id.terms)
        input = view.findViewById(R.id.inputTerm)
        empty = view.findViewById(R.id.tvEmpty)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.btnAdd).setOnClickListener { add() }

        view.findViewById<TextView>(R.id.tvBuiltIn).text =
            GeminiSummarizer.KNOWN_TERMS.joinToString(", ")

        render()
    }

    private fun add() {

        val term =
            input.text.toString().trim()

        if (term.isEmpty()) {
            return
        }

        if (settings.knownTerms.none { it.equals(term, ignoreCase = true) }) {
            settings.knownTerms = settings.knownTerms + term
        }

        input.setText("")
        render()
    }

    private fun render() {

        val inflater =
            LayoutInflater.from(requireContext())

        val saved =
            settings.knownTerms

        empty.isVisible = saved.isEmpty()

        terms.removeAllViews()

        saved.forEach { term ->

            val chip =
                inflater.inflate(R.layout.item_tag, terms, false) as TextView

            chip.text = getString(R.string.term_with_remove, term)

            chip.setOnClickListener {
                settings.knownTerms = settings.knownTerms.filterNot { it == term }
                render()
            }

            terms.addView(chip)
        }
    }
}

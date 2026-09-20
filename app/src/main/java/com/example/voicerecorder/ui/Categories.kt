package com.example.voicerecorder.ui

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.example.voicerecorder.R
import com.example.voicerecorder.summary.GeminiSummarizer

/**
 * How one category looks: icon, colours and card backgrounds.
 */
data class CategoryStyle(
    val name: String,
    @DrawableRes val icon: Int,
    @DrawableRes val circle: Int,
    @DrawableRes val pill: Int,
    @DrawableRes val card: Int,
    @ColorRes val color: Int
)

object Categories {

    private val thoughts =
        CategoryStyle(
            name = "Thoughts",
            icon = R.drawable.ic_brain,
            circle = R.drawable.bg_circle_thoughts,
            pill = R.drawable.bg_pill_thoughts,
            card = R.drawable.bg_note_thoughts,
            color = R.color.thoughts
        )

    private val idea =
        CategoryStyle(
            name = "Idea",
            icon = R.drawable.ic_bulb,
            circle = R.drawable.bg_circle_idea,
            pill = R.drawable.bg_pill_idea,
            card = R.drawable.bg_note_idea,
            color = R.color.idea
        )

    private val remember =
        CategoryStyle(
            name = "Remember",
            icon = R.drawable.ic_check,
            circle = R.drawable.bg_circle_remember,
            pill = R.drawable.bg_pill_remember,
            card = R.drawable.bg_note_remember,
            color = R.color.remember
        )

    private val gossip =
        CategoryStyle(
            name = "Random Gossip",
            icon = R.drawable.ic_chat,
            circle = R.drawable.bg_circle_gossip,
            pill = R.drawable.bg_pill_gossip,
            card = R.drawable.bg_note_gossip,
            color = R.color.gossip
        )

    /** In the order the categories are defined for Gemini. */
    val all: List<CategoryStyle> =
        GeminiSummarizer.CATEGORIES.map { of(it) }

    fun of(
        category: String
    ): CategoryStyle =
        when (category) {
            thoughts.name -> thoughts
            idea.name -> idea
            remember.name -> remember
            else -> gossip
        }
}

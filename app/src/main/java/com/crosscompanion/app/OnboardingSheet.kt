package com.crosscompanion.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.crosscompanion.app.databinding.ItemOnboardingCardBinding
import com.crosscompanion.app.databinding.SheetOnboardingBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class OnboardingSheet : BottomSheetDialogFragment() {

    private data class Card(val emoji: String, val title: String, val body: String, val action: String? = null)

    private val cards = listOf(
        Card(
            "🧩", "Welcome, word nerd!",
            "Cross Companion is a free, unofficial sidekick for crosswithfriends.com. Solve together with " +
                "friends, full screen, and with a pocket full of learning tools for when a clue gets cheeky.",
        ),
        Card(
            "🔑", "About signing in",
            "Google doesn't allow its sign-in inside apps that embed websites, so the \"Sign in with Google\" " +
                "button won't work here.\n\nUse an email and password instead. Already signed up with Google? " +
                "Open crosswithfriends.com in your browser, set a password on your account, then log in here.",
        ),
        Card(
            "💡", "The hint engine",
            "Stuck? Tap the yellow lightbulb (bottom right). It nudges you past the stumbling block without " +
                "taking the joy out of the solve. You decide how much help you get.",
        ),
        Card(
            "📚", "Word or term mode",
            "Never heard of ETUI or ESNE? Search any word, name or idiom to get its definition, trivia and " +
                "origin. Save the good ones to your journal so they stick for next time.",
        ),
        Card(
            "🔎", "Clue mode",
            "Paste a tricky clue like \"Ship's front\" and get plausible answers grouped by letter count, " +
                "straight from Google's AI Overview. Use it sparingly. We won't tell.",
        ),
        Card(
            "📓", "Journal & Google Doc sync",
            "Your journal keeps every word you've learned, even offline. Connect a Google Doc with a " +
                "two-minute Apps Script setup and new entries land in your Doc with one tap.",
            "Open setup guide",
        ),
    )

    private lateinit var b: SheetOnboardingBinding
    private val dots = mutableListOf<View>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        b = SheetOnboardingBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        b.pager.adapter = CardAdapter()
        buildDots()
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                val last = position == cards.lastIndex
                b.nextButton.setText(if (last) R.string.lets_solve else R.string.next)
                b.skipButton.visibility = if (last) View.INVISIBLE else View.VISIBLE
            }
        })
        b.nextButton.setOnClickListener {
            if (b.pager.currentItem == cards.lastIndex) dismiss() else b.pager.currentItem += 1
        }
        b.skipButton.setOnClickListener { dismiss() }
    }

    private fun buildDots() {
        val size = (8 * resources.displayMetrics.density).toInt()
        val gap = (5 * resources.displayMetrics.density).toInt()
        cards.forEach { _ ->
            val dot = View(requireContext()).apply {
                background = ContextCompat.getDrawable(context, R.drawable.dot)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = gap; marginEnd = gap }
            }
            dots += dot
            b.dots.addView(dot)
        }
        updateDots(0, animate = false)
    }

    private fun updateDots(selected: Int, animate: Boolean = true) {
        val active = ContextCompat.getColor(requireContext(), R.color.brand)
        val inactive = ContextCompat.getColor(requireContext(), R.color.dot_inactive)
        dots.forEachIndexed { i, dot ->
            val on = i == selected
            dot.background.mutate().setTint(if (on) active else inactive)
            val scale = if (on) 1.4f else 1f
            if (animate) dot.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
            else { dot.scaleX = scale; dot.scaleY = scale }
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.Holder>() {
        inner class Holder(val v: ItemOnboardingCardBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemOnboardingCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = cards.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = cards[position]
            with(holder.v) {
                emoji.text = card.emoji
                title.text = card.title
                body.text = card.body
                action.visibility = if (card.action != null) View.VISIBLE else View.GONE
                action.text = card.action
                action.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
            }
        }
    }

    companion object {
        const val TAG = "onboarding"
    }
}

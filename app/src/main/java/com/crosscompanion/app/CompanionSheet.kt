package com.crosscompanion.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crosscompanion.app.databinding.DialogAddCategoryBinding
import com.crosscompanion.app.databinding.DialogSettingsBinding
import com.crosscompanion.app.databinding.ItemJournalEntryBinding
import com.crosscompanion.app.databinding.SheetCompanionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.BreakIterator

class CompanionSheet : BottomSheetDialogFragment() {

    private var _b: SheetCompanionBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs(requireContext()) }
    private val journal by lazy { Journal(requireContext()) }
    private val journalAdapter = JournalAdapter()

    private var currentResult: LookupResult? = null
    private var selectedCategory: String? = null
    private var lookupJob: Job? = null
    private var clueUrl: String? = null

    private val behavior: BottomSheetBehavior<FrameLayout>?
        get() = (dialog as? BottomSheetDialog)?.behavior

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = SheetCompanionBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        setUpWordTab()
        setUpClueTab()
        setUpJournalTab()
        showTab(0)
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        sheet.layoutParams.height = (resources.displayMetrics.heightPixels * 0.9).toInt()
        behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        b.clue.answerWeb.destroy()
        _b = null
        super.onDestroyView()
    }

    private fun showTab(position: Int) {
        b.word.root.isVisible = position == 0
        b.clue.root.isVisible = position == 1
        b.journal.root.isVisible = position == 2
        if (position == 2) refreshJournal()
        // BottomSheetBehavior caches its nested scrolling child on layout; re-layout so it
        // tracks the scroller of the tab that is now visible.
        (b.root.parent as? View)?.requestLayout()
    }

    private fun hideKeyboard(v: View) {
        requireContext().getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    // region Word tab

    private fun setUpWordTab() = with(b.word) {
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runLookup(v.text.toString()); true } else false
        }
        inputLayout.setEndIconOnClickListener { runLookup(input.text.toString()) }
        saveButton.setOnClickListener { currentResult?.let { saveResult(it) } }
        syncButton.setOnClickListener { currentResult?.let { syncLookup(it) } }
        googleButton.setOnClickListener { currentResult?.let { requireContext().googleSearch(it.term) } }
    }

    private fun runLookup(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        hideKeyboard(b.word.input)
        lookupJob?.cancel()
        with(b.word) {
            progress.isVisible = true
            message.isVisible = false
            resultCard.isVisible = false
            relatedLabel.isVisible = false
            relatedChips.removeAllViews()
        }
        lookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { WordLookup.lookup(query) }
            val w = _b?.word ?: return@launch
            w.progress.isVisible = false
            result.onSuccess { r ->
                if (r == null) showWordMessage(getString(R.string.nothing_found)) else showResult(r)
            }.onFailure {
                showWordMessage(getString(R.string.lookup_failed))
            }
        }
    }

    private fun showWordMessage(text: String) {
        b.word.message.text = text
        b.word.message.isVisible = true
    }

    private fun showResult(r: LookupResult) = with(b.word) {
        currentResult = r.takeIf { it.summary.isNotEmpty() }
        if (r.summary.isNotEmpty()) {
            resultCard.isVisible = true
            term.text = r.term.uppercase()
            selectedCategory = r.category
            buildCategoryChips()
            summary.text = r.summary
            setOptional(originLabel, origin, r.etymology)
            setOptional(usageLabel, usage, r.usage?.let { "“$it”" })
        } else {
            showWordMessage(getString(R.string.nothing_found))
        }
        relatedLabel.isVisible = r.related.isNotEmpty()
        r.related.forEach { title ->
            relatedChips.addView(Chip(requireContext()).apply {
                text = title
                setOnClickListener {
                    input.setText(title)
                    runLookup(title)
                }
            })
        }
    }

    private fun setOptional(label: TextView, value: TextView, text: String?) {
        label.isVisible = text != null
        value.isVisible = text != null
        value.text = text
    }

    /** Built-in plus custom categories as single-select chips, with the current pick checked. */
    private fun buildCategoryChips() = with(b.word) {
        categoryChips.setOnCheckedStateChangeListener(null)
        categoryChips.removeAllViews()
        var selectedChip: Chip? = null
        Categories.all(requireContext()).forEach { category ->
            val chip = (layoutInflater.inflate(R.layout.item_category_chip, categoryChips, false) as Chip).apply {
                id = View.generateViewId()
                text = category.label
                tag = category.label
                if (category.custom) setOnLongClickListener { confirmRemoveCategory(category); true }
            }
            categoryChips.addView(chip)
            if (category.label == selectedCategory) selectedChip = chip
        }
        selectedChip?.let { chip ->
            chip.isChecked = true
            categoryScroll.post { categoryScroll.smoothScrollTo((chip.left - 24).coerceAtLeast(0), 0) }
        }
        categoryChips.setOnCheckedStateChangeListener { group, ids ->
            ids.firstOrNull()?.let { selectedCategory = group.findViewById<Chip>(it).tag as String }
        }
    }

    private fun confirmRemoveCategory(category: Category) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.remove_category, category.label))
            .setPositiveButton(R.string.remove) { _, _ ->
                Categories.removeCustom(requireContext(), category)
                if (selectedCategory == category.label) selectedCategory = currentResult?.category
                if (currentResult != null) buildCategoryChips()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveResult(r: LookupResult) {
        viewLifecycleOwner.lifecycleScope.launch {
            val added = journal.add(r.toEntry(selectedCategory ?: r.category))
            requireContext().toast(if (added) R.string.saved else R.string.already_saved)
        }
    }

    /** Syncing from the Word tab also saves the term, so the journal knows it's in the Doc. */
    private fun syncLookup(r: LookupResult) {
        viewLifecycleOwner.lifecycleScope.launch {
            val existing = journal.findByTerm(r.term)
            if (existing?.synced == true) {
                requireContext().toast(R.string.already_synced)
                return@launch
            }
            val entry = existing ?: r.toEntry(selectedCategory ?: r.category).also { journal.add(it) }
            syncEntries(listOf(entry))
        }
    }

    // endregion

    // region Clue tab

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setUpClueTab() = with(b.clue) {
        with(answerWeb.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Present as the device's own Chrome version: drop only WebView's "wv"/"Version/4.0" markers.
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }
        answerWeb.addJavascriptInterface(ClueBridge(), "ClueBridge")
        answerWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host.orEmpty()
                val path = uri.path.orEmpty()
                // Keep searches, Google's own redirects, consent and the "unusual traffic" check
                // (which the user completes themselves) inline; send every other link to the browser.
                val stayInline = (host == "google.com" || host.endsWith(".google.com")) &&
                    (request.isRedirect || path == "/search" || path.startsWith("/sorry") ||
                        host.startsWith("consent."))
                if (!stayInline) requireContext().openExternal(uri.toString())
                return !stayInline
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(isolateScript, null)
            }
        }

        // Keep drags inside the answer card from moving or dismissing the sheet.
        answerWeb.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    behavior?.isDraggable = false
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> behavior?.isDraggable = true
            }
            false
        }

        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runClue(v.text.toString()); true } else false
        }
        inputLayout.setEndIconOnClickListener { runClue(input.text.toString()) }
        openBrowser.setOnClickListener { clueUrl?.let { requireContext().openExternal(it) } }
    }

    private val isolateScript by lazy {
        requireContext().assets.open("clue_isolate.js").bufferedReader().use { it.readText() }
    }

    private fun runClue(raw: String) {
        val clue = raw.trim()
        if (clue.isEmpty()) return
        hideKeyboard(b.clue.input)
        val url = "https://www.google.com/search?hl=en&q=" + Uri.encode("$clue crossword clue")
        clueUrl = url
        with(b.clue) {
            empty.isVisible = false
            notice.isVisible = false
            banner.isVisible = false
            answerWeb.visibility = View.INVISIBLE
            progress.isVisible = true
            openBrowser.isEnabled = true
            answerWeb.loadUrl(url)
        }
    }

    private inner class ClueBridge {
        @JavascriptInterface
        fun onFound() = reveal(found = true)

        @JavascriptInterface
        fun onNotFound() = reveal(found = false, notice = R.string.clue_no_overview)

        @JavascriptInterface
        fun onChallenge() = reveal(found = false, notice = R.string.clue_challenge)

        private fun reveal(found: Boolean, notice: Int? = null) {
            val v = view ?: return
            v.post {
                val clue = _b?.clue ?: return@post
                clue.progress.isVisible = false
                clue.answerWeb.visibility = View.VISIBLE
                clue.notice.isVisible = notice != null
                notice?.let { clue.notice.setText(it) }
                if (found) flashBanner()
            }
        }
    }

    /** Raw answers can't be saved from here; nudge people to the Word tab instead. */
    private fun flashBanner() {
        val banner = b.clue.banner
        banner.alpha = 0f
        banner.isVisible = true
        banner.animate().alpha(1f).setDuration(250).withEndAction {
            banner.animate().alpha(0.4f).setDuration(250).withEndAction {
                banner.animate().alpha(1f).setDuration(250).start()
            }.start()
        }.start()
    }

    // endregion

    // region Journal tab

    private fun setUpJournalTab() = with(b.journal) {
        syncAllButton.setOnClickListener { syncAll() }
        addCategoryButton.setOnClickListener { showAddCategoryDialog() }
        settingsButton.setOnClickListener { showSettingsDialog() }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = journalAdapter
    }

    private fun showSettingsDialog() {
        val v = DialogSettingsBinding.inflate(layoutInflater)
        v.webhookInput.setText(prefs.webhookUrl.orEmpty())
        v.guideButton.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.doc_settings)
            .setView(v.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Validate before closing; a plain positive listener would always dismiss.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = v.webhookInput.text.toString().trim()
            if (url.isNotEmpty() && !Webhook.isValidUrl(url)) {
                v.webhookLayout.error = getString(R.string.webhook_invalid)
                return@setOnClickListener
            }
            prefs.webhookUrl = url
            requireContext().toast(R.string.webhook_saved)
            dialog.dismiss()
        }
    }

    private fun showAddCategoryDialog() {
        val v = DialogAddCategoryBinding.inflate(layoutInflater)
        val size = (44 * resources.displayMetrics.density).toInt()
        Categories.EMOJI_PRESETS.forEach { emoji ->
            v.emojiRow.addView(TextView(requireContext()).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size)
                setBackgroundResource(TypedValue().also {
                    requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                }.resourceId)
                setOnClickListener {
                    v.emojiInput.setText(emoji)
                    v.emojiInput.setSelection(v.emojiInput.length())
                    v.emojiLayout.error = null
                }
            })
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.new_category)
            .setView(v.root)
            .setPositiveButton(R.string.create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val emoji = firstEmoji(v.emojiInput.text.toString())
            val name = v.nameInput.text.toString().trim().replace(Regex("\\s+"), " ")
            v.emojiLayout.error = if (emoji == null) getString(R.string.emoji_required) else null
            v.nameLayout.error = if (name.isEmpty()) getString(R.string.name_required) else null
            if (emoji == null || name.isEmpty()) return@setOnClickListener
            val category = Category(emoji, name.take(Categories.NAME_MAX), custom = true)
            if (!Categories.addCustom(requireContext(), category)) {
                v.nameLayout.error = getString(R.string.name_taken)
                return@setOnClickListener
            }
            requireContext().toast(R.string.category_added)
            if (currentResult != null) buildCategoryChips()
            dialog.dismiss()
        }
    }

    /** First grapheme of the input if it's a symbol rather than a letter or digit, else null. */
    private fun firstEmoji(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val it = BreakIterator.getCharacterInstance().apply { setText(text) }
        val first = text.substring(0, it.next())
        return first.takeUnless { Character.isLetterOrDigit(first.codePointAt(0)) }
    }

    private fun refreshJournal() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = journal.all()
            val j = _b?.journal ?: return@launch
            journalAdapter.submit(entries)
            j.empty.isVisible = entries.isEmpty()
            j.summary.text = getString(R.string.journal_summary, entries.size, entries.count { !it.synced })
            j.syncAllButton.isEnabled = entries.isNotEmpty()
        }
    }

    /** Sends only entries that haven't reached the Doc yet. */
    private fun syncAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pending = journal.all().filterNot { it.synced }
            if (pending.isEmpty()) {
                requireContext().toast(R.string.all_synced)
                return@launch
            }
            syncEntries(pending)
        }
    }

    private fun syncEntries(entries: List<JournalEntry>) {
        val url = prefs.webhookUrl
        if (url == null) {
            val subject = entries.singleOrNull()?.term ?: getString(R.string.sync_all)
            promptForWebhook(subject, Journal.exportMarkdown(entries))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { Webhook.sendEntries(url, entries) }
                .onSuccess {
                    journal.markSynced(entries.map { it.id })
                    context?.toast(
                        if (entries.size == 1) getString(R.string.synced)
                        else getString(R.string.synced_count, entries.size),
                    )
                    if (_b?.journal?.root?.isVisible == true) refreshJournal()
                }
                .onFailure { e -> context?.toast(getString(R.string.sync_failed, e.message ?: "error")) }
        }
    }

    private fun promptForWebhook(subject: String, markdown: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.no_webhook_title)
            .setMessage(R.string.no_webhook_body)
            .setPositiveButton(R.string.set_up_webhook) { _, _ -> showSettingsDialog() }
            .setNegativeButton(R.string.share_instead) { _, _ -> requireContext().shareText(subject, markdown) }
            .show()
    }

    private fun confirmDelete(entry: JournalEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.delete_confirm, entry.term))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    journal.delete(entry.id)
                    refreshJournal()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class JournalAdapter : RecyclerView.Adapter<JournalAdapter.Holder>() {
        private var items: List<JournalEntry> = emptyList()

        inner class Holder(val v: ItemJournalEntryBinding) : RecyclerView.ViewHolder(v.root)

        @SuppressLint("NotifyDataSetChanged")
        fun submit(entries: List<JournalEntry>) {
            items = entries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemJournalEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            with(holder.v) {
                term.text = e.term
                meta.text = "${e.category} · ${e.displayDate}"
                trivia.text = e.trivia
                origin.isVisible = e.etymology != null
                origin.text = e.etymology?.let { "Origin: $it" }
                copy.setOnClickListener {
                    requireContext().copyToClipboard(e.term, e.toMarkdown())
                    requireContext().toast(R.string.copied)
                }
                badge.setText(if (e.synced) R.string.badge_synced else R.string.badge_new)
                badge.setTextColor(color(if (e.synced) R.color.badge_synced_fg else R.color.badge_new_fg))
                badge.background.mutate().setTint(color(if (e.synced) R.color.badge_synced_bg else R.color.badge_new_bg))
                sync.isEnabled = !e.synced
                sync.setOnClickListener { syncEntries(listOf(e)) }
                delete.setOnClickListener { confirmDelete(e) }
            }
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(requireContext(), res)

    // endregion

    companion object {
        const val TAG = "companion"
    }
}

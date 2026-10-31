package com.crosscompanion.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

class CompanionSheet : BottomSheetDialogFragment() {

    private var _b: SheetCompanionBinding? = null
    private val b get() = _b!!
    private val prefs by lazy { Prefs(requireContext()) }
    private val journal by lazy { Journal(requireContext()) }
    private val journalAdapter = JournalAdapter()

    private var currentResult: LookupResult? = null
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
        syncButton.setOnClickListener { currentResult?.let { syncEntry(it.toEntry()) } }
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
            category.text = r.category
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

    private fun saveResult(r: LookupResult) {
        viewLifecycleOwner.lifecycleScope.launch {
            val added = journal.add(r.toEntry())
            requireContext().toast(if (added) R.string.saved else R.string.already_saved)
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
        webhookInput.setText(prefs.webhookUrl.orEmpty())
        saveWebhook.setOnClickListener { saveWebhookUrl() }
        webhookInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveWebhookUrl(); true } else false
        }
        guideButton.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
        exportButton.setOnClickListener { exportJournal() }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = journalAdapter
    }

    private fun saveWebhookUrl() = with(b.journal) {
        val url = webhookInput.text.toString().trim()
        if (url.isNotEmpty() && !Webhook.isValidUrl(url)) {
            webhookLayout.error = getString(R.string.webhook_invalid)
            return
        }
        webhookLayout.error = null
        prefs.webhookUrl = url
        hideKeyboard(webhookInput)
        requireContext().toast(R.string.webhook_saved)
    }

    private fun refreshJournal() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = journal.all()
            val j = _b?.journal ?: return@launch
            journalAdapter.submit(entries)
            j.empty.isVisible = entries.isEmpty()
            j.exportButton.isEnabled = entries.isNotEmpty()
        }
    }

    private fun exportJournal() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = journal.all()
            if (entries.isEmpty()) return@launch
            val url = prefs.webhookUrl
            if (url == null) {
                promptForWebhook(getString(R.string.export_journal), Journal.exportMarkdown(entries))
                return@launch
            }
            runCatching { Webhook.sendExport(url, entries) }
                .onSuccess { context?.toast(R.string.synced) }
                .onFailure { e -> context?.toast(getString(R.string.sync_failed, e.message ?: "error")) }
        }
    }

    private fun syncEntry(entry: JournalEntry) {
        val url = prefs.webhookUrl
        if (url == null) {
            promptForWebhook(entry.term, entry.toMarkdown())
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { Webhook.sendEntry(url, entry) }
                .onSuccess { context?.toast(R.string.synced) }
                .onFailure { e -> context?.toast(getString(R.string.sync_failed, e.message ?: "error")) }
        }
    }

    private fun promptForWebhook(subject: String, markdown: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.no_webhook_title)
            .setMessage(R.string.no_webhook_body)
            .setPositiveButton(R.string.set_up_webhook) { _, _ ->
                b.tabs.getTabAt(2)?.select()
                b.journal.webhookInput.requestFocus()
            }
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
                sync.setOnClickListener { syncEntry(e) }
                delete.setOnClickListener { confirmDelete(e) }
            }
        }
    }

    // endregion

    companion object {
        const val TAG = "companion"
    }
}

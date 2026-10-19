package com.deeplinknow.example

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.deeplinknow.DLN
import com.deeplinknow.DeferredDeepLink
import com.deeplinknow.QueryValue
import kotlinx.coroutines.launch

/**
 * DeepLinkNow Android example.
 *
 * The whole integration is three calls:
 *   1. DLN.init(context, apiKey) + DLN.awaitInitialization()   once, at startup
 *   2. DLN.checkDeferredDeepLink()                             once, on first launch after install
 *   3. DLN.parseDeepLink(url)                                  for App Links that open the app
 *
 * Everything else on this screen is for testing.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // "test_api_key" is DeepLinkNow's demo key (returns sample data). Use your app's key from
        // the dashboard (Dashboard → your app → API keys) to test real links.
        private const val API_KEY = "test_api_key"
    }

    private lateinit var initStatus: TextView
    private lateinit var deferredResult: TextView
    private lateinit var openedLinkResult: TextView
    private lateinit var minConfidenceGroup: RadioGroup
    private lateinit var checkAgainButton: Button
    private lateinit var resetButton: Button

    /**
     * Fingerprint matches below this score are ignored. Off (null) by default: the SDK returns
     * the best candidate and you decide using confidenceScore / matchQuality.
     */
    private val minConfidence: Double?
        get() = when (minConfidenceGroup.checkedRadioButtonId) {
            R.id.minConfidence30 -> 30.0
            R.id.minConfidence50 -> 50.0
            R.id.minConfidence75 -> 75.0
            else -> null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initStatus = findViewById(R.id.initStatus)
        deferredResult = findViewById(R.id.deferredResult)
        openedLinkResult = findViewById(R.id.openedLinkResult)
        minConfidenceGroup = findViewById(R.id.minConfidenceGroup)
        checkAgainButton = findViewById(R.id.checkAgainButton)
        resetButton = findViewById(R.id.resetButton)

        checkAgainButton.setOnClickListener { checkDeferredDeepLink(force = true) }
        resetButton.setOnClickListener {
            DLN.resetDeferredDeepLink()
            deferredResult.text = "Deferred link state cleared. Tap “Check again” or relaunch the app."
        }

        // 1 + 2: initialize, then check for a deferred deep link once (first launch).
        // DLN.init returns immediately; awaitInitialization() reports success or the error.
        // (A real app would usually call DLN.init in Application.onCreate.)
        DLN.init(applicationContext, API_KEY, enableLogs = true)
        lifecycleScope.launch {
            DLN.awaitInitialization()
                .onSuccess {
                    initStatus.text = "Initialized (${it.linkDomains.joinToString()})"
                    checkAgainButton.isEnabled = true
                    resetButton.isEnabled = true
                    checkDeferredDeepLink(force = false)
                }
                .onFailure { initStatus.text = "Init failed: ${it.message}" }
        }

        // 3: App Links that launched the app.
        handleOpenedLink(intent)
    }

    // 3: App Links that arrive while the app is running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenedLink(intent)
    }

    /**
     * Resolves to null when there is no link, or when this install has already been checked
     * (unless [force]). Android reads the Play Install Referrer first, then falls back to
     * fingerprint matching. Errors arrive as Result.failure(DLNException); it never throws.
     */
    private fun checkDeferredDeepLink(force: Boolean) {
        lifecycleScope.launch {
            DLN.checkDeferredDeepLink(minConfidence = minConfidence, force = force)
                .onSuccess { link ->
                    deferredResult.text = link?.let(::describe) ?: "No deferred deep link"
                    // In a real app: link?.let { navigateTo(it.path, it.queryParams) }
                }
                .onFailure { deferredResult.text = "Error: ${it.message}" }
        }
    }

    private fun handleOpenedLink(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        // null when the URL is not on one of your app's DeepLinkNow domains.
        val link = DLN.parseDeepLink(url)
        openedLinkResult.text = if (link == null) {
            "Not a DeepLinkNow link: $url"
        } else {
            "path: ${link.path}\nqueryParams: ${describe(link.queryParams)}\nfullPath: ${link.fullPath}"
        }
    }

    private fun describe(link: DeferredDeepLink): String = """
        path: ${link.path}
        queryParams: ${describe(link.queryParams)}
        fullPath: ${link.fullPath}
        source: ${link.source.value}
        confidenceScore: ${link.confidenceScore}
        matchQuality: ${link.matchQuality}
    """.trimIndent()

    /** {ref=nl, beta=true, tag=[a, b]} */
    private fun describe(query: Map<String, QueryValue>): String =
        query.mapValues { it.value.toPlain() }.toString()
}

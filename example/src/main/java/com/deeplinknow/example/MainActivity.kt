package com.deeplinknow.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeplinknow.android.DeepLinkNow
import com.deeplinknow.android.DeferredUserResponse
import com.deeplinknow.example.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel : ViewModel() {
    private var isInitialized = false

    fun initialize() {
        if (!isInitialized) {
            viewModelScope.launch {
                DeepLinkNow.initialize(
                    apiKey = "web-test-api-key",
                    config = DeepLinkNow.Config(enableLogs = true)
                )
                isInitialized = true
            }
        }
    }

    suspend fun findDeferredUser(): DeferredUserResponse? {
        return DeepLinkNow.findDeferredUser()
    }

    suspend fun checkClipboard(): String? {
        return DeepLinkNow.checkClipboard()
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        binding.initButton.setOnClickListener {
            viewModel.initialize()
            binding.initButton.text = "Initialized!"
        }

        binding.visitExternalButton.setOnClickListener {
            val url = "https://test-app.deeplinknow.com/sample-link?is_test=true&hello=world&otherParams=false"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.findDeferredUserButton.setOnClickListener {
            viewModel.viewModelScope.launch {
                val response = viewModel.findDeferredUser()
                response?.matches?.let { matches ->
                    showMatches(matches)
                }
            }
        }

        binding.checkClipboardButton.setOnClickListener {
            viewModel.viewModelScope.launch {
                val result = viewModel.checkClipboard()
                binding.clipboardResult.apply {
                    text = "Clipboard Result: $result"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showMatches(matches: List<DeferredUserResponse.Match>) {
        binding.matchesContainer.visibility = View.VISIBLE
        binding.matchesList.removeAllViews()

        // Add header
        val headerView = TextView(this).apply {
            text = "Match Results"
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        binding.matchesList.addView(headerView)

        // Add match cards
        matches.forEach { match ->
            val matchCard = createMatchCard(match)
            binding.matchesList.addView(matchCard)
        }
    }

    private fun createMatchCard(match: DeferredUserResponse.Match): CardView {
        val card = CardView(this).apply {
            radius = resources.getDimension(com.google.android.material.R.dimen.cardview_default_radius)
            elevation = resources.getDimension(com.google.android.material.R.dimen.cardview_default_elevation)
            layoutParams = CardView.LayoutParams(
                CardView.LayoutParams.MATCH_PARENT,
                CardView.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 16)
            }
        }

        val content = LayoutInflater.from(this).inflate(
            android.R.layout.simple_list_item_1,
            card,
            false
        ) as TextView

        val matchInfo = buildString {
            appendLine("Confidence: ${match.confidenceScore.toFixed(1)}%")
            appendLine()

            match.deeplink?.let { deeplink ->
                appendLine("Deeplink Info:")
                appendLine("URL: ${deeplink.targetUrl}")
                if (deeplink.campaignId != null) {
                    appendLine("Campaign: ${deeplink.campaignId}")
                }
                appendLine("Matched: ${dateFormat.format(Date(deeplink.matchedAt))}")
                appendLine("Expires: ${dateFormat.format(Date(deeplink.expiresAt))}")
                appendLine()
            }

            match.matchDetails?.let { details ->
                appendLine("Match Details:")
                appendLine("IP Match: ${if (details.ipMatch.matched) "✓" else "✗"} (Weight: ${details.ipMatch.score.toFixed(0)})")
                appendLine("Device Match: ${if (details.deviceMatch.matched) "✓" else "✗"} (Weight: ${details.deviceMatch.score.toFixed(0)})")
                appendLine("Locale Match: ${if (details.localeMatch.matched) "✓" else "✗"} (Weight: ${details.localeMatch.score.toFixed(0)})")
            }
        }

        content.text = matchInfo
        content.setPadding(32, 32, 32, 32)
        card.addView(content)

        return card
    }
}

private fun Double.toFixed(digits: Int): String = String.format("%.${digits}f", this) 
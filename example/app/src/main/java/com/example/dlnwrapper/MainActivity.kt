package com.example.dlnwrapper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.deeplinknow.DLN
import com.deeplinknow.example.R
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private var isInitialized = false
    private var matches: List<Match>? = null
    private var clipboardResult: String? = null

    // UI components
    private lateinit var initDlnButton: Button
    private lateinit var visitExternalLinkButton: Button
    private lateinit var findDeferredUserButton: Button
    private lateinit var checkClipboardButton: Button
    private lateinit var clipboardResultText: TextView
    private lateinit var matchesHeaderText: TextView
    private lateinit var matchesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        initDlnButton = findViewById(R.id.initDlnButton)
        visitExternalLinkButton = findViewById(R.id.visitExternalLinkButton)
        findDeferredUserButton = findViewById(R.id.findDeferredUserButton)
        checkClipboardButton = findViewById(R.id.checkClipboardButton)
        clipboardResultText = findViewById(R.id.clipboardResultText)
        matchesHeaderText = findViewById(R.id.matchesHeaderText)
        matchesContainer = findViewById(R.id.matchesContainer)

        // Set click listeners
        initDlnButton.setOnClickListener { initDln() }
        visitExternalLinkButton.setOnClickListener { visitExternalDeeplinkPage() }
        findDeferredUserButton.setOnClickListener { findDeferredUser() }
        checkClipboardButton.setOnClickListener { checkClipboard() }
    }

    private fun initDln() {
        // Initialize DeepLinkNow with the API key
        lifecycleScope.launch {
            try {
                DLN.init(this@MainActivity, "web-test-api-key", true)
                
                // Wait a bit to ensure initialization is complete
                kotlinx.coroutines.delay(500)
                
                // Get instance to verify initialization
                DLN.getInstance()
                
                // Update button text and status
                isInitialized = true
                initDlnButton.text = "Initialized!"
            } catch (e: Exception) {
                Log.e("DeepLinkNow", "Error initializing DLN", e)
                initDlnButton.text = "Init Failed"
            }
        }
    }

    private fun visitExternalDeeplinkPage() {
        // Open the test URL in a browser
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://test-app.deeplinknow.com/sample-link?is_test=true&hello=world&otherParams=false"
        ))
        startActivity(intent)
    }

    private fun findDeferredUser() {
        lifecycleScope.launch {
            try {
                // Get DLN instance
                val dlnInstance = DLN.getInstance()
                
                // Call findDeferredUser on the instance - this returns MatchResponse
                val response = dlnInstance.findDeferredUser()
                
                // Log the result
                Log.d("DeepLinkNow", "Response: $response")
                
                if (response != null && response.matches.isNotEmpty()) {
                    // Convert from DLN SDK Match objects to our app's Match objects
                    val matchList = response.matches.map { sdkMatch ->
                        // Convert deeplink data if available
                        val deeplink = sdkMatch.deeplink?.let {
                            Match.Deeplink(
                                targetUrl = it.targetUrl,
                                campaignId = it.campaignId,
                                matchedAt = it.matchedAt,
                                expiresAt = it.expiresAt
                            )
                        }
                        
                        // Convert match details
                        val matchDetails = Match.MatchDetails(
                            ipMatch = Match.MatchDetails.MatchItem(
                                matched = sdkMatch.matchDetails.ipMatch.matched,
                                score = sdkMatch.matchDetails.ipMatch.score
                            ),
                            deviceMatch = Match.MatchDetails.MatchItem(
                                matched = sdkMatch.matchDetails.deviceMatch.matched,
                                score = sdkMatch.matchDetails.deviceMatch.score
                            ),
                            localeMatch = Match.MatchDetails.MatchItem(
                                matched = sdkMatch.matchDetails.localeMatch.matched,
                                score = sdkMatch.matchDetails.localeMatch.score
                            )
                        )
                        
                        // Create our app's Match object
                        Match(
                            confidenceScore = sdkMatch.confidenceScore,
                            deeplink = deeplink,
                            matchDetails = matchDetails
                        )
                    }
                    
                    // Update the UI with the matches
                    matches = matchList
                    updateMatchesUI()
                } else {
                    Log.d("DeepLinkNow", "No matches found")
                    matches = emptyList()
                    updateMatchesUI()
                }
            } catch (e: Exception) {
                Log.e("DeepLinkNow", "Error finding deferred user", e)
            }
        }
    }

    private fun checkClipboard() {
        lifecycleScope.launch {
            try {
                // Call the DLN SDK to check clipboard
                val result = DLN.getInstance().checkClipboard()
                clipboardResult = result
                
                // Update the UI
                clipboardResultText.text = "Clipboard Result: $result"
                clipboardResultText.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("DeepLinkNow", "Error checking clipboard", e)
            }
        }
    }

    private fun updateMatchesUI() {
        // Clear existing match views
        matchesContainer.removeAllViews()
        
        val currentMatches = matches
        if (currentMatches != null && currentMatches.isNotEmpty()) {
            // Show header
            matchesHeaderText.visibility = View.VISIBLE
            
            // Add match views
            for (match in currentMatches) {
                val matchView = MatchView(this)
                matchView.setMatch(match)
                matchesContainer.addView(matchView)
            }
        } else {
            // Hide header if no matches
            matchesHeaderText.visibility = View.GONE
        }
    }
}
package com.deeplinknow.example

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
import com.deeplinknow.DeepLinkCallback
import com.deeplinknow.DeepLinkData
import com.deeplinknow.example.R
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity(), DeepLinkCallback {

    private var isInitialized = false
    private var matches: List<Match>? = null
    private var clipboardResult: String? = null

    // UI components
    private lateinit var initDlnButton: Button
    private lateinit var visitExternalLinkButton: Button
    private lateinit var findDeferredUserButton: Button
    private lateinit var checkClipboardButton: Button
    private lateinit var testDeepLinkButton: Button
    private lateinit var testManualParsingButton: Button
    private lateinit var clearResultsButton: Button
    private lateinit var clipboardResultText: TextView
    private lateinit var matchesHeaderText: TextView
    private lateinit var matchesContainer: LinearLayout
    private lateinit var deepLinkResultText: TextView
    private lateinit var logOutputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        initDlnButton = findViewById(R.id.initDlnButton)
        visitExternalLinkButton = findViewById(R.id.visitExternalLinkButton)
        findDeferredUserButton = findViewById(R.id.findDeferredUserButton)
        checkClipboardButton = findViewById(R.id.checkClipboardButton)
        testDeepLinkButton = findViewById(R.id.testDeepLinkButton)
        testManualParsingButton = findViewById(R.id.testManualParsingButton)
        clearResultsButton = findViewById(R.id.clearResultsButton)
        clipboardResultText = findViewById(R.id.clipboardResultText)
        matchesHeaderText = findViewById(R.id.matchesHeaderText)
        matchesContainer = findViewById(R.id.matchesContainer)
        deepLinkResultText = findViewById(R.id.deepLinkResultText)
        logOutputText = findViewById(R.id.logOutputText)

        // Set click listeners
        initDlnButton.setOnClickListener { initDln() }
        visitExternalLinkButton.setOnClickListener { visitExternalDeeplinkPage() }
        findDeferredUserButton.setOnClickListener { findDeferredUser() }
        checkClipboardButton.setOnClickListener { checkClipboard() }
        testDeepLinkButton.setOnClickListener { testDeepLinkHandling() }
        testManualParsingButton.setOnClickListener { testManualParsing() }
        clearResultsButton.setOnClickListener { clearResults() }

        // Register deep link callback (safe to call before initialization)
        DLN.setDeepLinkCallback(this)
        logToUI("🔗 Deep link callback registered")

        // Auto-initialize the SDK for better UX
        initDln()

        // Handle deep link if app was opened from one
        handleIncomingDeepLink(intent)
        
        // Show initial instructions
        logToUI("🚀 DeepLinkNow Example App initialized")
        logToUI("💡 Try these features:")
        logToUI("1. SDK will auto-initialize")
        logToUI("2. Test deep link handling")
        logToUI("3. Test manual URL parsing")
        logToUI("4. Open the app from a deep link")
    }

    private fun initDln() {
        // Initialize DeepLinkNow with the API key
        logToUI("⚙️ Initializing DLN SDK...")
        runOnUiThread {
            initDlnButton.text = "Initializing..."
            initDlnButton.isEnabled = false
        }
        
        lifecycleScope.launch {
            try {
                DLN.init(this@MainActivity, "web-test-api-key", true)
                logToUI("✅ DLN.init() called successfully")
                
                // Wait a bit to ensure initialization is complete
                kotlinx.coroutines.delay(500)
                
                // Get instance to verify initialization
                val instance = DLN.getInstance()
                logToUI("🔍 SDK instance retrieved")
                
                // Update button text and status
                isInitialized = true
                runOnUiThread {
                    initDlnButton.text = "Initialized!"
                    initDlnButton.isEnabled = true
                }
                logToUI("🎉 DLN SDK fully initialized and ready!")
                logToUI("🔗 Deep link callback is now active")
            } catch (e: Exception) {
                Log.e("DeepLinkNow", "Error initializing DLN", e)
                runOnUiThread {
                    initDlnButton.text = "Init Failed - Retry"
                    initDlnButton.isEnabled = true
                }
                logToUI("❌ DLN initialization failed: ${e.message}")
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

    // Handle incoming deep links when app is opened
    private fun handleIncomingDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            val url = uri.toString()
            Log.d("DeepLinkNow", "App opened with deep link: $url")
            logToUI("🔗 App opened with deep link!")
            logToUI("📋 URL: $url")
            
            // Use DLN SDK to handle the deep link
            val handled = DLN.handleDeepLink(url)
            if (!handled) {
                Log.d("DeepLinkNow", "Deep link was not handled by DLN SDK")
                if (isInitialized) {
                    logToUI("❌ Deep link not handled (invalid domain)")
                } else {
                    logToUI("⏳ Deep link will be processed after SDK initialization")
                }
            } else {
                logToUI("✅ Deep link handled successfully!")
            }
        } ?: run {
            logToUI("📱 App opened normally (no deep link)")
        }
    }

    // Override onNewIntent to handle deep links when app is already running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingDeepLink(intent)
    }

    // Deep link callback implementation
    override fun onDeepLinkOpen(deepLinkData: DeepLinkData) {
        Log.d("DeepLinkNow", "Deep link opened! Route: '${deepLinkData.route}', Params: ${deepLinkData.params}")
        
        // Update UI on main thread
        runOnUiThread {
            val resultText = "Deep Link Captured!\n" +
                    "Route: ${deepLinkData.route}\n" +
                    "Params: ${deepLinkData.params}"
            
            deepLinkResultText.text = resultText
            deepLinkResultText.visibility = View.VISIBLE
        }
        
        // Handle the deep link based on route and params
        when (deepLinkData.route) {
            "this_is_a_test_url/anything/else/here" -> {
                // Handle the specific test route
                Log.d("DeepLinkNow", "Handling test route with params: ${deepLinkData.params}")
                logToUI("📝 Test route handler called")
                
                // Example: Extract specific parameters
                val blah = deepLinkData.params["blah"]
                val test = deepLinkData.params["test"]
                val hello = deepLinkData.params["hello"]
                
                Log.d("DeepLinkNow", "blah=$blah, test=$test, hello=$hello")
                logToUI("📊 Parameters: blah=$blah, test=$test, hello=$hello")
            }
            "product/detail" -> {
                val productId = deepLinkData.params["id"]
                val category = deepLinkData.params["category"]
                logToUI("🛍️ Product detail: ID=$productId, Category=$category")
            }
            "user/profile" -> {
                val userId = deepLinkData.params["user_id"]
                val tab = deepLinkData.params["tab"]
                logToUI("👤 User profile: ID=$userId, Tab=$tab")
            }
            "promotion/offer" -> {
                val offerCode = deepLinkData.params["code"]
                val discount = deepLinkData.params["discount"]
                logToUI("🎁 Promotion: Code=$offerCode, Discount=$discount")
            }
            else -> {
                // Handle other routes
                Log.d("DeepLinkNow", "Handling route: ${deepLinkData.route}")
                logToUI("🔗 Generic route handler for: ${deepLinkData.route}")
            }
        }
    }

    private fun testDeepLinkHandling() {
        logToUI("🧪 Testing deep link handling...")
        
        // Test various deep link scenarios including the user's specific case
        val testUrls = listOf(
            "https://jvgtest123.deeplinknow.com/this_is_a_test_url?params=123&test=true&hello=world",  // User's specific case
            "https://jvgtest123.deeplinknow.com/this_is_a_test_url/anything/else/here?blah=123&test=true&hello=world",
            "https://jvgtest123.deeplinknow.com/product/detail?id=12345&category=electronics",
            "https://jvgtest123.deeplinknow.com/user/profile?user_id=abc123&tab=settings",
            "https://jvgtest123.deeplinknow.com/promotion/offer?code=SAVE20&discount=20",
            "https://deeplinknow.com/test?basic=true",  // Base domain test
            "https://invalid-domain.com/test?param=value"  // This should be rejected
        )
        
        testUrls.forEach { url ->
            logToUI("📤 Testing: $url")
            
            // Test domain validation first
            val uri = android.net.Uri.parse(url)
            val domain = uri.host ?: ""
            val isValidDomain = try {
                DLN.getInstance().isValidDomain(domain)
            } catch (e: Exception) {
                false
            }
            
            logToUI("🌐 Domain '$domain': ${if (isValidDomain) "VALID" else "INVALID"}")
            
            val handled = DLN.handleDeepLink(url)
            if (!handled) {
                logToUI("❌ Not handled")
            } else {
                logToUI("✅ Successfully handled!")
            }
            logToUI("---")
        }
    }

    private fun testManualParsing() {
        logToUI("🔍 Testing manual URL parsing...")
        
        val testUrls = listOf(
            "https://jvgtest123.deeplinknow.com/sample/path?param1=value1&param2=value2",
            "https://deeplinknow.com/another/path?test=true&number=42",
            "https://test.deeplink.now/path?param=123",
            "https://anydomain.deeplinknow.com/test?hello=world",
            "https://invalid-domain.com/test?param=value"  // This should return null
        )
        
        testUrls.forEach { url ->
            logToUI("📋 Parsing: $url")
            
            // Test domain validation directly
            val uri = android.net.Uri.parse(url)
            val domain = uri.host ?: ""
            val isValid = try {
                DLN.getInstance().isValidDomain(domain)
            } catch (e: Exception) {
                false
            }
            
            logToUI("🌐 Domain '$domain' is ${if (isValid) "VALID" else "INVALID"}")
            
            val result = try {
                DLN.getInstance().parseDeepLink(url)
            } catch (e: Exception) {
                null
            }
            
            if (result != null) {
                val (path, params) = result
                logToUI("✅ Path: '$path', Params: $params")
            } else {
                logToUI("❌ Parsing failed")
            }
        }
    }

    private fun clearResults() {
        logToUI("🧹 Clearing all results...")
        
        // Clear all result displays
        deepLinkResultText.visibility = View.GONE
        clipboardResultText.visibility = View.GONE
        matchesHeaderText.visibility = View.GONE
        matchesContainer.removeAllViews()
        
        // Clear data
        matches = null
        clipboardResult = null
        
        // Clear log output but keep the clear action
        logOutputText.text = "📝 Log Output:\n🧹 Results cleared"
        logOutputText.visibility = View.VISIBLE
    }

    private fun logToUI(message: String) {
        runOnUiThread {
            val currentText = logOutputText.text?.toString() ?: "📝 Log Output:"
            val newText = "$currentText\n$message"
            logOutputText.text = newText
            logOutputText.visibility = View.VISIBLE
        }
    }
}
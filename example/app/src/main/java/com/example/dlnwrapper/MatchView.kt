package com.example.dlnwrapper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.deeplinknow.example.R
import java.text.SimpleDateFormat
import java.util.Locale

class MatchView(context: Context) : CardView(context) {

    private lateinit var match: Match
    private val view: View

    // References to the views
    private val confidenceScoreView: TextView
    private val deeplinkSection: View
    private val deeplinkUrlView: TextView
    private val campaignIdView: TextView
    private val matchedAtView: TextView
    private val expiresAtView: TextView
    private val ipMatchStatusView: TextView
    private val ipMatchScoreView: TextView
    private val deviceMatchStatusView: TextView
    private val deviceMatchScoreView: TextView
    private val localeMatchStatusView: TextView
    private val localeMatchScoreView: TextView

    init {
        // Inflate the layout
        val inflater = LayoutInflater.from(context)
        view = inflater.inflate(R.layout.match_item, this, true)

        // Get references to all views
        confidenceScoreView = view.findViewById(R.id.confidenceScore)
        deeplinkSection = view.findViewById(R.id.deeplinkSection)
        deeplinkUrlView = view.findViewById(R.id.deeplinkUrl)
        campaignIdView = view.findViewById(R.id.campaignId)
        matchedAtView = view.findViewById(R.id.matchedAt)
        expiresAtView = view.findViewById(R.id.expiresAt)
        ipMatchStatusView = view.findViewById(R.id.ipMatchStatus)
        ipMatchScoreView = view.findViewById(R.id.ipMatchScore)
        deviceMatchStatusView = view.findViewById(R.id.deviceMatchStatus)
        deviceMatchScoreView = view.findViewById(R.id.deviceMatchScore)
        localeMatchStatusView = view.findViewById(R.id.localeMatchStatus)
        localeMatchScoreView = view.findViewById(R.id.localeMatchScore)
    }

    fun setMatch(match: Match) {
        this.match = match
        
        // Set confidence score
        confidenceScoreView.text = "Confidence: ${String.format("%.1f", match.confidenceScore)}%"
        
        // Handle deeplink information
        if (match.deeplink != null) {
            deeplinkSection.visibility = View.VISIBLE
            deeplinkUrlView.text = "URL: ${match.deeplink.targetUrl}"
            
            if (match.deeplink.campaignId != null) {
                campaignIdView.visibility = View.VISIBLE
                campaignIdView.text = "Campaign: ${match.deeplink.campaignId}"
            } else {
                campaignIdView.visibility = View.GONE
            }
            
            val dateFormat = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.US)
            matchedAtView.text = "Matched: ${formatDateString(match.deeplink.matchedAt)}"
            expiresAtView.text = "Expires: ${formatDateString(match.deeplink.expiresAt)}"
        } else {
            deeplinkSection.visibility = View.GONE
        }
        
        // Handle match details
        val details = match.matchDetails
        
        // IP Match
        ipMatchStatusView.text = "IP Match: ${if (details.ipMatch.matched) "✓" else "✗"}"
        ipMatchScoreView.text = "Weight: ${details.ipMatch.score.toInt()}"
        
        // Device Match
        deviceMatchStatusView.text = "Device Match: ${if (details.deviceMatch.matched) "✓" else "✗"}"
        deviceMatchScoreView.text = "Weight: ${details.deviceMatch.score.toInt()}"
        
        // Locale Match
        localeMatchStatusView.text = "Locale Match: ${if (details.localeMatch.matched) "✓" else "✗"}"
        localeMatchScoreView.text = "Weight: ${details.localeMatch.score.toInt()}"
    }
    
    private fun formatDateString(isoDateString: String): String {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            val outputFormat = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.US)
            val date = isoFormat.parse(isoDateString)
            return outputFormat.format(date!!)
        } catch (e: Exception) {
            return isoDateString
        }
    }
} 
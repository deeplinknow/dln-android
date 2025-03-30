package com.example.dlnwrapper

/**
 * This Match class is a simplified version of the DeepLinkNow SDK's Match class
 * adapted for our sample app UI.
 */
data class Match(
    val confidenceScore: Double,
    val deeplink: Deeplink?,
    val matchDetails: MatchDetails
) {
    /**
     * Simplified version of DeeplinkMatch
     */
    data class Deeplink(
        val targetUrl: String,
        val campaignId: String?,
        val matchedAt: String,
        val expiresAt: String
    )

    /**
     * Simplified version of MatchDetails
     */
    data class MatchDetails(
        val ipMatch: MatchItem,
        val deviceMatch: MatchItem,
        val localeMatch: MatchItem
    ) {
        /**
         * Simplified version of MatchComponentDetails
         */
        data class MatchItem(
            val matched: Boolean,
            val score: Double
        )
    }
} 
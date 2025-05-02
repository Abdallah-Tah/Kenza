package com.example.kenza.auth

/**
 * Interface for providing Microsoft Graph API access tokens
 */
interface GraphTokenProvider {
    /**
     * Get a valid access token for Microsoft Graph API
     * @return the access token string or null if authentication failed
     */
    suspend fun getAccessToken(): String?
}
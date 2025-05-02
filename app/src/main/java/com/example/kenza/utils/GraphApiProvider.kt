package com.example.kenza.utils

/**
 * Provides singleton instances of Graph API components
 */
object GraphApiProvider {
    // Lazy-initialized singleton instance of GraphApiClient
    val graphApiClient: GraphApiClient by lazy {
        GraphApiClientImpl()
    }
    
    // Lazy-initialized singleton instance of GraphEmailService
    val graphEmailService: GraphEmailService by lazy {
        GraphEmailService(graphApiClient)
    }
}
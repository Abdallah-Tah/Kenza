package com.kenza.kenza.services

class EmailCleanerService(
    private val imapService: ImapService,
    private val openAIService: OpenAIService
) {
    // TODO: Implement logic to decide email actions (move/delete/unsubscribe)
}
package com.example.kenza.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * A queue system that processes email operations in controlled batches
 * to prevent overloading the Graph API and causing app freezes.
 */
class EmailProcessingQueue(
    private val scope: CoroutineScope,
    private val processFunction: suspend (EmailTask) -> Boolean,
    private val chunkSize: Int = 50,
    private val processingDelayMs: Long = 500,
    private val onStatsUpdate: (ProcessingStats) -> Unit = {}
) {
    companion object {
        private const val TAG = "EmailProcessingQueue"
    }

    private val mutex = Mutex()
    private val queue = mutableListOf<EmailTask>()
    private var isProcessing = false
    private var currentJob: Job? = null
    
    // Statistics
    private var totalProcessed = 0
    private var totalSucceeded = 0
    
    data class EmailTask(
        val id: String,
        val subject: String,
        val preview: String,
        val sender: String,
        val receivedDateTime: String,
        val accessToken: String
    )
    
    data class ProcessingStats(
        val queued: Int,
        val processed: Int,
        val succeeded: Int
    )
    
    /**
     * Adds an email to the processing queue
     */
    suspend fun enqueueEmail(
        id: String,
        subject: String,
        preview: String,
        sender: String,
        receivedDateTime: String,
        accessToken: String
    ) {
        mutex.withLock {
            queue.add(EmailTask(id, subject, preview, sender, receivedDateTime, accessToken))
            Log.d(TAG, "Enqueued email: $subject. Queue size: ${queue.size}")
            
            if (!isProcessing) {
                startProcessing()
            }
            updateStats()
        }
    }
    
    /**
     * Adds multiple emails to the processing queue at once
     */
    suspend fun enqueueEmails(tasks: List<EmailTask>) {
        if (tasks.isEmpty()) return
        
        mutex.withLock {
            queue.addAll(tasks)
            Log.d(TAG, "Enqueued ${tasks.size} emails. Total queue size: ${queue.size}")
            
            if (!isProcessing) {
                startProcessing()
            }
            updateStats()
        }
    }
    
    /**
     * Get current queue and processing statistics
     */
    suspend fun getStats(): ProcessingStats = mutex.withLock {
        return ProcessingStats(
            queued = queue.size,
            processed = totalProcessed,
            succeeded = totalSucceeded
        )
    }
    
    /**
     * Stops the processing queue. Any in-progress chunk will complete processing.
     */
    suspend fun stop() {
        mutex.withLock {
            currentJob?.cancel()
            isProcessing = false
            Log.d(TAG, "Processing queue stopped. ${queue.size} emails remain in queue.")
            updateStats()
        }
    }
    
    /**
     * Clears all statistics
     */
    suspend fun resetStats() {
        mutex.withLock {
            totalProcessed = 0
            totalSucceeded = 0
            updateStats()
        }
    }
    
    /**
     * Starts processing the queue in chunks
     */
    private fun startProcessing() {
        if (isProcessing) return
        
        isProcessing = true
        
        currentJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting queue processing")
            
            while (isActive) {
                val batch = mutex.withLock {
                    if (queue.isEmpty()) {
                        isProcessing = false
                        updateStats()
                        return@launch
                    }
                    
                    // Take at most chunkSize items
                    val tasksToProcess = queue.take(chunkSize)
                    queue.removeAll(tasksToProcess)
                    tasksToProcess
                }
                
                Log.d(TAG, "Processing batch of ${batch.size} emails")
                
                // Process the batch with a limited parallelism dispatcher to avoid overwhelming the API
                val jobs = batch.map { task ->
                    scope.launch(Dispatchers.IO.limitedParallelism(3)) {
                        try {
                            // Call the actual processing function
                            val result = processFunction(task)
                            
                            mutex.withLock {
                                totalProcessed++
                                if (result) totalSucceeded++
                                updateStats()
                            }
                            
                            Log.d(TAG, "Processed email ${task.id} (${task.subject}): $result")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing email ${task.id}: ${e.message}", e)
                            mutex.withLock {
                                totalProcessed++
                                updateStats()
                            }
                        }
                    }
                }
                
                // Wait for all jobs in this batch to complete
                jobs.forEach { it.join() }
                
                // Wait before processing the next batch to avoid throttling
                delay(processingDelayMs)
                
                // Check for throttling before proceeding to next batch
                if (GraphApiUtils.isThrottled()) {
                    val waitTime = GraphApiUtils.getRemainingThrottleTime()
                    if (waitTime > 0) {
                        Log.w(TAG, "API is throttled. Waiting ${waitTime}ms before processing next batch.")
                        delay(waitTime + 200) // Add a small buffer
                    }
                }
            }
        }
    }
    
    private fun updateStats() {
        val stats = ProcessingStats(
            queued = queue.size,
            processed = totalProcessed,
            succeeded = totalSucceeded
        )
        onStatsUpdate(stats)
    }
}
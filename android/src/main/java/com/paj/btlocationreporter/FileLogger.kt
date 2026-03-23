package com.paj.btlocationreporter

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dual logger for debugging the plugin:
 * 1. **Logcat** - Standard Android logging (Log.d/i/e)
 * 2. **File** - Writes to app files directory for persistent access
 *
 * To view logs:
 *   - **Android Studio**: Logcat panel, filter by "BTLR"
 *   - **File**: adb pull /data/data/{package}/files/bt-location-reporter.log
 *
 * Path on device: /data/data/{package}/files/bt-location-reporter.log
 */
object FileLogger {

    private const val LOG_FILE_NAME = "bt-location-reporter.log"
    private const val TAG = "BTLR"
    
    private var logFile: File? = null
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    /** When false, logging is disabled (except errors) */
    @Volatile
    var debugEnabled: Boolean = false
    
    /**
     * Initialize the logger with app context.
     * Call this once in the plugin's load() or service's onCreate().
     */
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        // Clear previous log on init
        logFile?.writeText("========== LOG SESSION STARTED ==========\n")
        log("FileLogger", "Log file: ${logFile?.absolutePath}")
    }
    
    /**
     * Log a message with class name prefix. Only logs if debugEnabled is true.
     */
    fun log(className: String, message: String) {
        if (!debugEnabled) return
        
        val prefix = "[BTLR - $className]"
        val timestamp = dateFormatter.format(Date())
        val logMessage = "$prefix [$timestamp] $message"
        
        // 1. Log to Logcat
        Log.d(TAG, "$prefix $message")
        
        // 2. Write to file
        writeToFile("$logMessage\n")
    }
    
    /**
     * Log an error message. Always logs regardless of debugEnabled.
     */
    fun error(className: String, message: String) {
        val prefix = "[BTLR - $className]"
        val timestamp = dateFormatter.format(Date())
        val logMessage = "$prefix [$timestamp] ERROR: $message"
        
        Log.e(TAG, "$prefix ERROR: $message")
        if (debugEnabled) {
            writeToFile("$logMessage\n")
        }
    }
    
    /**
     * Log an info message. Only logs if debugEnabled is true.
     */
    fun info(className: String, message: String) {
        if (!debugEnabled) return
        
        val prefix = "[BTLR - $className]"
        val timestamp = dateFormatter.format(Date())
        val logMessage = "$prefix [$timestamp] INFO: $message"
        
        Log.i(TAG, "$prefix $message")
        writeToFile("$logMessage\n")
    }
    
    private fun writeToFile(message: String) {
        try {
            logFile?.appendText(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }
    
    /**
     * Get the log file path.
     */
    fun getLogPath(): String = logFile?.absolutePath ?: ""
    
    /**
     * Get the log file contents.
     */
    fun getLogs(): String = try {
        logFile?.readText() ?: ""
    } catch (e: Exception) {
        "Error reading logs: ${e.message}"
    }
}

// ── Extension functions for convenient logging ────────────────────────────────

private fun Any.className(): String = this::class.java.simpleName

fun Any.LOG(message: String) = FileLogger.log(className(), message)
fun Any.LOG_ERROR(message: String) = FileLogger.error(className(), message)
fun Any.LOG_INFO(message: String) = FileLogger.info(className(), message)

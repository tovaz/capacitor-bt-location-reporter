import Foundation
import os

/**
 * Dual logger for debugging the plugin:
 * 1. **Xcode Console** - Uses Apple's Unified Logging (os.Logger) which appears in Xcode debugger and Console.app
 * 2. **File** - Writes to Documents/bt-location-reporter.log for persistent access
 *
 * To view logs:
 *   - **Xcode**: Logs appear in the Debug Console panel while running
 *   - **Console.app**: Filter by "BtLocationReporter" subsystem
 *   - **File**: Window > Devices and Simulators > Select device > Download Container
 *     Or enable iTunes File Sharing in Info.plist: UIFileSharingEnabled = YES
 *
 * Path on device: /var/mobile/Containers/Data/Application/{APP-UUID}/Documents/bt-location-reporter.log
 */
class FileLogger {
    
    static let shared = FileLogger()
    
    private let logFileName = "bt-location-reporter.log"
    private let dateFormatter: DateFormatter
    private let fileManager = FileManager.default
    private var logFileURL: URL?
    
    /// Apple's unified logging system - appears in Xcode debugger and Console.app
    private let osLogger: Logger
    
    /// Subsystem for filtering in Console.app
    private let subsystem = "com.paj.btlocationreporter"
    
    /// When false, logging is disabled (except errors)
    var debugEnabled: Bool = false
    
    private init() {
        dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        
        // Initialize os.Logger for Unified Logging
        osLogger = Logger(subsystem: subsystem, category: "Plugin")
        
        setupLogFile()
    }
    
    private func setupLogFile() {
        guard let documentsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            osLogger.error("Could not access Documents directory")
            return
        }
        
        logFileURL = documentsDir.appendingPathComponent(logFileName)
        
        // Clear previous log on app start
        if let url = logFileURL {
            try? fileManager.removeItem(at: url)
            fileManager.createFile(atPath: url.path, contents: nil, attributes: nil)
            log("========== LOG SESSION STARTED ==========")
            log("Log file path: \(url.path)")
        }
    }
    
    func log(_ message: String, level: OSLogType = .default, file: String = #file, function: String = #function, line: Int = #line) {
        // Skip non-error logs if debug is disabled
        if !debugEnabled && level != .error && level != .fault {
            return
        }
        
        let timestamp = dateFormatter.string(from: Date())
        let fileName = (file as NSString).lastPathComponent.replacingOccurrences(of: ".swift", with: "")
        let prefix = "[BTLR - \(fileName)]"
        let logMessage = "\(prefix) [\(timestamp)] \(function):\(line) → \(message)"
        let fileLogMessage = logMessage + "\n"
        
        // 1. Log to Xcode console using Unified Logging (os.Logger)
        //    This appears in Xcode debugger and Console.app
        switch level {
        case .debug:
            osLogger.debug("\(prefix, privacy: .public) \(function, privacy: .public):\(line) → \(message, privacy: .public)")
        case .info:
            osLogger.info("\(prefix, privacy: .public) \(function, privacy: .public):\(line) → \(message, privacy: .public)")
        case .error:
            osLogger.error("\(prefix, privacy: .public) \(function, privacy: .public):\(line) → \(message, privacy: .public)")
        case .fault:
            osLogger.fault("\(prefix, privacy: .public) \(function, privacy: .public):\(line) → \(message, privacy: .public)")
        default:
            osLogger.log("\(prefix, privacy: .public) \(function, privacy: .public):\(line) → \(message, privacy: .public)")
        }
        
        // 2. Also print to stdout (backup, appears in Xcode console area)
        print(logMessage)
        
        // 3. Write to file for persistent access
        writeToFile(fileLogMessage)
    }
    
    /// Log with error level (highlighted in Xcode and Console.app)
    func error(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .error, file: file, function: function, line: line)
    }
    
    /// Log with info level
    func info(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .info, file: file, function: function, line: line)
    }
    
    /// Log with debug level
    func debug(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .debug, file: file, function: function, line: line)
    }
    
    private func writeToFile(_ message: String) {
        guard let url = logFileURL else { return }
        
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            if let data = message.data(using: .utf8) {
                handle.write(data)
            }
            handle.closeFile()
        }
    }
    
    /// Returns the full path to the log file (useful for displaying to user)
    func getLogFilePath() -> String {
        return logFileURL?.path ?? "unknown"
    }
    
    /// Returns the log contents as a string
    func getLogContents() -> String {
        guard let url = logFileURL,
              let contents = try? String(contentsOf: url, encoding: .utf8) else {
            return ""
        }
        return contents
    }
    
    /// Clears the log file
    func clearLogs() {
        guard let url = logFileURL else { return }
        try? fileManager.removeItem(at: url)
        fileManager.createFile(atPath: url.path, contents: nil, attributes: nil)
        osLogger.info("Log file cleared")
    }
}

// MARK: - Global convenience functions

/// Standard log (default level) - appears in Xcode debugger and file
func LOG(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
    FileLogger.shared.log(message, level: .default, file: file, function: function, line: line)
}

/// Error log (highlighted in red in Xcode) - for errors and failures
func LOG_ERROR(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
    FileLogger.shared.error(message, file: file, function: function, line: line)
}

/// Info log - for important events
func LOG_INFO(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
    FileLogger.shared.info(message, file: file, function: function, line: line)
}

/// Debug log - for verbose debugging (may be filtered out in release builds)
func LOG_DEBUG(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
    FileLogger.shared.debug(message, file: file, function: function, line: line)
}

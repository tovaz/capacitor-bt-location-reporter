import Foundation

/**
 * Simple file logger for debugging the plugin.
 * Logs are written to: Documents/bt-location-reporter.log
 *
 * To access on device:
 *   - Xcode: Window > Devices and Simulators > Select device > Download Container
 *   - Or enable iTunes File Sharing in Info.plist: UIFileSharingEnabled = YES
 *
 * Path on device: /var/mobile/Containers/Data/Application/{APP-UUID}/Documents/bt-location-reporter.log
 */
class FileLogger {
    
    static let shared = FileLogger()
    
    private let logFileName = "bt-location-reporter.log"
    private let dateFormatter: DateFormatter
    private let fileManager = FileManager.default
    private var logFileURL: URL?
    
    private init() {
        dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        
        setupLogFile()
    }
    
    private func setupLogFile() {
        guard let documentsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            print("[FileLogger] ERROR: Could not access Documents directory")
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
    
    func log(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
        let timestamp = dateFormatter.string(from: Date())
        let fileName = (file as NSString).lastPathComponent.replacingOccurrences(of: ".swift", with: "")
        let logMessage = "[\(timestamp)] [\(fileName):\(line)] \(function) → \(message)\n"
        
        // Print to console as well
        print(logMessage, terminator: "")
        
        // Write to file
        guard let url = logFileURL else { return }
        
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            if let data = logMessage.data(using: .utf8) {
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
}

// Global convenience function
func LOG(_ message: String, file: String = #file, function: String = #function, line: Int = #line) {
    FileLogger.shared.log(message, file: file, function: function, line: line)
}

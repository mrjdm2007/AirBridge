import Foundation

struct MTPFile: Identifiable, Hashable, Sendable {
    let id: UInt32
    let name: String
    let size: Int64
}

struct MTPListing: Sendable {
    let deviceName: String
    let files: [MTPFile]
}

enum MTPError: LocalizedError {
    case missingTools
    case noDevice(String)
    case commandFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingTools:
            return "MTP tools are missing. Install the free libmtp dependency with: brew install libmtp"
        case .noDevice(let details):
            return details.isEmpty
                ? "No MTP device found. Unlock your phone and select USB File Transfer."
                : details
        case .commandFailed(let details):
            return details
        }
    }
}

enum MTPService {
    private static let searchPaths = [
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/opt/homebrew/opt/libmtp/bin",
        "/usr/local/opt/libmtp/bin"
    ]

    private static func tool(_ name: String) throws -> URL {
        for directory in searchPaths {
            let candidate = URL(fileURLWithPath: directory).appendingPathComponent(name)
            if FileManager.default.isExecutableFile(atPath: candidate.path) {
                return candidate
            }
        }
        throw MTPError.missingTools
    }

    @discardableResult
    private static func run(_ name: String, _ arguments: [String]) throws -> String {
        let executable = try tool(name)
        let process = Process()
        let output = Pipe()
        process.executableURL = executable
        process.arguments = arguments
        process.standardOutput = output
        process.standardError = output
        process.environment = [
            "PATH": searchPaths.joined(separator: ":"),
            "HOME": FileManager.default.homeDirectoryForCurrentUser.path
        ]
        do {
            try process.run()
        } catch {
            throw MTPError.commandFailed("Could not start \(name): \(error.localizedDescription)")
        }
        let data = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let text = String(data: data, encoding: .utf8) ?? ""
        guard process.terminationStatus == 0 else {
            throw MTPError.commandFailed(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return text
    }

    static func listFiles() throws -> MTPListing {
        let output = try run("mtp-files", [])
        guard output.contains("Listing File Information on Device with name:") else {
            throw MTPError.noDevice(output.contains("No Devices have been found") ? "" : output.trimmingCharacters(in: .whitespacesAndNewlines))
        }

        let deviceLine = output.components(separatedBy: .newlines)
            .first(where: { $0.contains("Listing File Information on Device with name:") }) ?? ""
        let name = deviceLine
            .components(separatedBy: "Listing File Information on Device with name:")
            .last?
            .components(separatedBy: " [SN:")
            .first?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let files = parseFiles(output)
        return MTPListing(deviceName: (name?.isEmpty == false ? name! : "Android device"), files: files)
    }

    private static func parseFiles(_ output: String) -> [MTPFile] {
        let lines = output.components(separatedBy: .newlines)
        var records: [(UInt32, String, Int64)] = []
        var currentID: UInt32?
        var currentName: String?
        var currentSize: Int64 = 0

        func flush() {
            if let id = currentID, let name = currentName, !name.isEmpty {
                records.append((id, name, currentSize))
            }
        }

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("File ID:") {
                flush()
                currentID = UInt32(trimmed.replacingOccurrences(of: "File ID:", with: "").trimmingCharacters(in: .whitespaces))
                currentName = nil
                currentSize = 0
            } else if trimmed.hasPrefix("Filename:") {
                currentName = String(trimmed.dropFirst("Filename:".count)).trimmingCharacters(in: .whitespaces)
            } else if trimmed.hasPrefix("File size") {
                let pattern = #"File size:?\s*([0-9]+)"#
                if let range = trimmed.range(of: pattern, options: .regularExpression) {
                    let number = trimmed[range].split(whereSeparator: { !$0.isNumber }).last
                    currentSize = Int64(number ?? "0") ?? 0
                }
            }
        }
        flush()
        return records.map { MTPFile(id: $0.0, name: $0.1, size: $0.2) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func upload(_ file: URL) throws {
        guard FileManager.default.fileExists(atPath: file.path) else {
            throw MTPError.commandFailed("The selected file is no longer available: \(file.lastPathComponent)")
        }
        _ = try run("mtp-sendfile", [file.path])
    }

    static func download(_ file: MTPFile, to destination: URL) throws {
        _ = try run("mtp-getfile", [String(file.id), destination.path])
    }
}

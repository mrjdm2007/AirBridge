import Combine
import Foundation
import Network
import CryptoKit

struct LANPeer: Identifiable {
    let id: String
    let endpoint: NWEndpoint
}

@MainActor
final class LANTransferController: ObservableObject {
    @Published private(set) var peers: [LANPeer] = []
    @Published var status = "Starting local network discovery…"
    @Published var error: String?

    private let queue = DispatchQueue(label: "com.airbridge.lan", qos: .userInitiated)
    private var browser: NWBrowser?
    private var listener: NWListener?

    func start() {
        startListener()
        startBrowser()
    }

    private func startListener() {
        do {
            let listener = try NWListener(using: .tcp)
            listener.service = NWListener.Service(
                name: Host.current().localizedName ?? "AirBridge Mac",
                type: "_airbridge._tcp.",
                domain: "local.",
                txtRecord: nil
            )
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: self?.queue ?? .global())
                Task { @MainActor in self?.receive(on: connection, parser: IncomingParser()) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    if case .failed(let failure) = state {
                        self?.error = "Wi-Fi listener: \(failure.localizedDescription)"
                    }
                }
            }
            self.listener = listener
            listener.start(queue: queue)
        } catch {
            self.error = "Could not start Wi-Fi receiver: \(error.localizedDescription)"
        }
    }

    private func startBrowser() {
        let browser = NWBrowser(for: .bonjour(type: "_airbridge._tcp.", domain: "local."), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap { result -> LANPeer? in
                guard case .service(let name, _, _, _) = result.endpoint else { return nil }
                return LANPeer(id: name, endpoint: result.endpoint)
            }
            Task { @MainActor in
                self?.peers = found
                if found.isEmpty { self?.status = "Waiting for Android on the same local network" }
                else { self?.status = "Found \(found.count) device(s) on Wi-Fi" }
            }
        }
        browser.stateUpdateHandler = { [weak self] state in
            if case .failed(let failure) = state {
                Task { @MainActor in self?.error = "Wi-Fi discovery: \(failure.localizedDescription)" }
            }
        }
        self.browser = browser
        browser.start(queue: queue)
    }

    func send(_ file: URL, to peer: LANPeer) {
        status = "Sending \(file.lastPathComponent)…"
        error = nil
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let accessed = file.startAccessingSecurityScopedResource()
            defer { if accessed { file.stopAccessingSecurityScopedResource() } }
            do {
                try Self.sendFile(file, endpoint: peer.endpoint, queue: self?.queue ?? .global())
                Task { @MainActor in self?.status = "Sent \(file.lastPathComponent) to \(peer.id)" }
            } catch {
                Task { @MainActor in self?.error = error.localizedDescription }
            }
        }
    }

    private static func sendFile(_ url: URL, endpoint: NWEndpoint, queue: DispatchQueue) throws {
        let connection = NWConnection(to: endpoint, using: .tcp)
        defer { connection.cancel() }
        let ready = DispatchSemaphore(value: 0)
        let finished = DispatchSemaphore(value: 0)
        var connectionError: NWError?
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.signal()
            case .failed(let error): connectionError = error; ready.signal()
            case .cancelled: finished.signal()
            default: break
            }
        }
        connection.start(queue: queue)
        guard ready.wait(timeout: .now() + 20) == .success else {
            connection.cancel()
            throw LANError.timeout
        }
        if let connectionError { throw connectionError }

        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
        guard size <= 20 * 1024 * 1024 * 1024 else { throw LANError.fileTooLarge }
        let filename = url.lastPathComponent
        let name = Data(filename.utf8)
        guard !name.isEmpty && name.count <= 1024 else { throw LANError.invalidName }
        var hasher = SHA256()
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        while true {
            let chunk = try input.read(upToCount: 1024 * 1024) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        let digest = Data(hasher.finalize())

        var header = Data("ABR1".utf8)
        header.appendBE(UInt32(name.count))
        header.appendBE(size)
        header.append(digest)
        header.append(name)
        try sendData(header, through: connection)

        try input.seek(toOffset: 0)
        while true {
            let chunk = try input.read(upToCount: 256 * 1024) ?? Data()
            if chunk.isEmpty { break }
            try sendData(chunk, through: connection)
        }
        let sendDone = DispatchSemaphore(value: 0)
        connection.send(content: nil, contentContext: .defaultMessage, isComplete: true, completion: .contentProcessed { _ in sendDone.signal() })
        guard sendDone.wait(timeout: .now() + 30) == .success else {
            throw LANError.timeout
        }

        let ack = DispatchSemaphore(value: 0)
        var acknowledged = false
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { data, _, _, _ in
            acknowledged = data?.first == 0x06
            ack.signal()
        }
        guard ack.wait(timeout: .now() + 120) == .success, acknowledged else {
            connection.cancel()
            throw LANError.transferRejected
        }
        connection.cancel()
        _ = finished.wait(timeout: .now() + 1)
    }

    private static func sendData(_ data: Data, through connection: NWConnection) throws {
        let semaphore = DispatchSemaphore(value: 0)
        var sendError: NWError?
        connection.send(content: data, completion: .contentProcessed { error in
            sendError = error
            semaphore.signal()
        })
        guard semaphore.wait(timeout: .now() + 120) == .success else { throw LANError.timeout }
        if let sendError { throw sendError }
    }

    private func receive(on connection: NWConnection, parser: IncomingParser) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            if let data, !data.isEmpty {
                do {
                    if try parser.consume(data) {
                        connection.send(content: Data([0x06]), completion: .contentProcessed { _ in connection.cancel() })
                        Task { @MainActor in self?.status = "Received \(parser.filename) in Downloads/AirBridge" }
                        return
                    }
                } catch {
                    parser.abort()
                    Task { @MainActor in self?.error = error.localizedDescription }
                    connection.cancel()
                    return
                }
            }
            if let error {
                Task { @MainActor in self?.error = "Incoming transfer: \(error.localizedDescription)" }
                connection.cancel()
            } else if complete {
                parser.abort()
                Task { @MainActor in self?.error = "The sender disconnected before the file completed." }
                connection.cancel()
            } else {
                Task { @MainActor in self?.receive(on: connection, parser: parser) }
            }
        }
    }
}

private final class IncomingParser {
    private var buffer = Data()
    private var parsedHeader = false
    private var expectedSize: UInt64 = 0
    private var written: UInt64 = 0
    private var expectedDigest = Data()
    private var hasher = SHA256()
    private var output: FileHandle?
    private var temporaryURL: URL?
    private(set) var filename = "file"

    func consume(_ data: Data) throws -> Bool {
        buffer.append(data)
        if !parsedHeader {
            guard buffer.count >= 48 else { return false }
            guard buffer.prefix(4) == Data("ABR1".utf8) else { throw LANError.badHeader }
            let nameLength = Int(buffer.readBE(UInt32.self, at: 4))
            expectedSize = buffer.readBE(UInt64.self, at: 8)
            expectedDigest = buffer.subdata(in: 16..<48)
            guard (1...1024).contains(nameLength), expectedSize <= 20 * 1024 * 1024 * 1024 else { throw LANError.badHeader }
            let free = (try? FileManager.default.attributesOfFileSystem(forPath: FileManager.default.temporaryDirectory.path)[.systemFreeSize] as? NSNumber)?.uint64Value ?? 0
            guard expectedSize <= free else { throw LANError.insufficientStorage }
            guard buffer.count >= 48 + nameLength else { return false }
            filename = Self.safeName(String(data: buffer.subdata(in: 48..<(48 + nameLength)), encoding: .utf8) ?? "file")
            buffer.removeFirst(48 + nameLength)
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent("airbridge-\(UUID().uuidString).part")
            FileManager.default.createFile(atPath: temp.path, contents: nil)
            temporaryURL = temp
            output = try FileHandle(forWritingTo: temp)
            parsedHeader = true
        }

        let remaining = expectedSize - written
        let amount = min(UInt64(buffer.count), remaining)
        if amount > 0 {
            let chunk = buffer.prefix(Int(amount))
            try output?.write(contentsOf: chunk)
            hasher.update(data: Data(chunk))
            buffer.removeFirst(Int(amount))
            written += amount
        }
        guard written == expectedSize else { return false }
        guard buffer.isEmpty else { throw LANError.badHeader }
        try output?.close()
        output = nil
        guard Data(hasher.finalize()) == expectedDigest else {
            cleanup()
            throw LANError.integrity
        }
        let directory = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("AirBridge", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = uniqueDestination(in: directory, filename: filename)
        guard let temporaryURL else { throw LANError.badHeader }
        try FileManager.default.moveItem(at: temporaryURL, to: destination)
        self.temporaryURL = nil
        return true
    }

    func abort() { cleanup() }

    private func cleanup() {
        try? output?.close()
        output = nil
        if let temporaryURL { try? FileManager.default.removeItem(at: temporaryURL) }
        temporaryURL = nil
    }

    private func uniqueDestination(in directory: URL, filename: String) -> URL {
        let original = directory.appendingPathComponent(filename)
        if !FileManager.default.fileExists(atPath: original.path) { return original }
        let base = original.deletingPathExtension().lastPathComponent
        let ext = original.pathExtension
        var index = 1
        while true {
            let candidateName = ext.isEmpty ? "\(base) (\(index))" : "\(base) (\(index)).\(ext)"
            let candidate = directory.appendingPathComponent(candidateName)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            index += 1
        }
    }

    private static func safeName(_ input: String) -> String {
        let cleaned = input.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .components(separatedBy: .controlCharacters).joined()
            .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        return String(cleaned.prefix(180)).isEmpty ? "AirBridge-file" : String(cleaned.prefix(180))
    }
}

private enum LANError: LocalizedError {
    case timeout, invalidName, transferRejected, badHeader, integrity, fileTooLarge, insufficientStorage
    var errorDescription: String? {
        switch self {
        case .timeout: return "The network transfer timed out."
        case .invalidName: return "The selected filename is not valid."
        case .transferRejected: return "The Android device did not confirm the transfer."
        case .badHeader: return "The incoming AirBridge transfer header is invalid."
        case .integrity: return "The received file failed its SHA-256 integrity check."
        case .fileTooLarge: return "Files larger than 20 GiB are not supported."
        case .insufficientStorage: return "There is not enough free storage to receive this file."
        }
    }
}

private extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) {
        var big = value.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }
    func readBE<T: FixedWidthInteger>(_ type: T.Type, at offset: Int) -> T {
        self[offset..<(offset + MemoryLayout<T>.size)].withUnsafeBytes { $0.loadUnaligned(as: T.self).bigEndian }
    }
}

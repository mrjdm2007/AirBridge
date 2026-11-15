import AppKit
import SwiftUI
import UniformTypeIdentifiers

func airBridgeItemSize(_ url: URL) -> Int64 {
    if url.hasDirectoryPath, let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) {
        var total: Int64 = 0
        for case let child as URL in enumerator {
            if let values = try? child.resourceValues(forKeys: [.fileSizeKey]), let size = values.fileSize {
                total += Int64(size)
            }
        }
        return total
    }
    return (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
}

@main
struct AirBridgeApp: App {
    @NSApplicationDelegateAdaptor(AirBridgeAppDelegate.self) private var appDelegate

    var body: some Scene {
        // AirBridge is a menu-bar utility; the status-item click opens the floating
        // Drop Tray directly, without presenting an intermediate menu.
        Settings {
            EmptyView()
        }
    }
}

@MainActor
final class AirBridgeAppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = item.button {
            button.image = NSImage(systemSymbolName: "arrow.up.arrow.down.square", accessibilityDescription: "AirBridge")
            button.image?.isTemplate = true
            button.toolTip = "AirBridge · Open Drop Tray"
            button.target = self
            button.action = #selector(openDropTray(_:))
        }
        statusItem = item
    }

    @objc private func openDropTray(_ sender: Any?) {
        AirBridgeTrayWindow.shared.show()
    }
}

@MainActor
final class AirBridgeTrayWindow: NSObject, NSWindowDelegate {
    static let shared = AirBridgeTrayWindow()
    private var window: NSWindow?

    func show() {
        if let window, window.isVisible || window.isMiniaturized {
            if window.isMiniaturized { window.deminiaturize(nil) }
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            return
        }
        // A closed NSWindow must be recreated; retaining the old instance alone
        // can leave the menu-bar command pointing at an invisible window.
        window = nil

        let tray = AirBridgePopover()
        let hosting = NSHostingController(rootView: tray)
        let panel = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 374, height: 560),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        panel.title = "AirBridge Drop Tray"
        panel.titlebarAppearsTransparent = true
        panel.titleVisibility = .hidden
        panel.isMovableByWindowBackground = true
        panel.isReleasedWhenClosed = false
        panel.delegate = self
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.contentViewController = hosting
        panel.center()
        panel.setFrameAutosaveName("AirBridgeDropTray")
        window = panel

        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        guard let closedWindow = notification.object as? NSWindow,
              closedWindow === window else { return }
        window = nil
    }
}

struct AirBridgePopover: View {
    @StateObject private var mtp = MTPController()
    @StateObject private var lan = LANTransferController()
    @State private var showingImporter = false
    @State private var showingMTPImporter = false
    @State private var selectedMode = "Wireless"
    @State private var selectedCategory = "Files"
    @State private var selectedURLs: [URL] = []
    @State private var showHistory = false
    @State private var dropError: String?

    private var selectedBytes: Int64 {
        selectedURLs.reduce(Int64(0)) { $0 + airBridgeItemSize($1) }
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "arrow.up.arrow.down.square.fill").font(.system(size: 20))
                VStack(alignment: .leading, spacing: 2) {
                    Text("AirBridge").font(.system(size: 17, weight: .semibold))
                    Text(lan.peers.isEmpty ? "Waiting for connection" : "Connected · Moto Edge 50 Neo")
                        .font(.system(size: 10)).foregroundStyle(.secondary)
                }
                Spacer()
                Button { showHistory.toggle() } label: {
                    Image(systemName: "clock").font(.system(size: 13, weight: .medium))
                }
                .buttonStyle(.plain).help(showHistory ? "Hide history" : "Show history")
            }

            HStack(spacing: 6) {
                modeButton("Wireless", icon: "wifi")
                modeButton("Wired", icon: "cable.connector")
            }

            if showHistory {
                VStack(alignment: .leading, spacing: 7) {
                    HStack {
                        Text("RECENT TRANSFERS").font(.system(size: 9, weight: .semibold)).tracking(0.8).foregroundStyle(.secondary)
                        Spacer()
                        Button("Clear") { lan.clearHistory() }.font(.system(size: 10)).buttonStyle(.plain)
                    }
                    if lan.history.isEmpty {
                        Text("No transfers yet").font(.system(size: 11)).foregroundStyle(.secondary)
                    } else {
                        ForEach(lan.history.prefix(8)) { item in
                            HStack(spacing: 8) {
                                Image(systemName: item.direction == "Sent" ? "arrow.up.circle" : "arrow.down.circle").foregroundStyle(.secondary)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(item.filename).font(.system(size: 10, weight: .medium)).lineLimit(1)
                                    Text(item.date.formatted(date: .omitted, time: .shortened)).font(.system(size: 9)).foregroundStyle(.tertiary)
                                }
                                Spacer()
                                Text(ByteCountFormatter.string(fromByteCount: Int64(clamping: item.bytes), countStyle: .file))
                                    .font(.system(size: 9, design: .monospaced)).foregroundStyle(.tertiary)
                            }
                        }
                    }
                }
                .padding(10).background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 13))
            } else if selectedMode == "Wireless" {
                VStack(spacing: 9) {
                    HStack(spacing: 7) {
                        Circle().fill(lan.peers.isEmpty ? Color.secondary : Color.green).frame(width: 6, height: 6)
                        Text(lan.peers.isEmpty ? "Waiting for connection" : "Moto Edge 50 Neo connected")
                            .font(.system(size: 11, weight: .medium))
                        Spacer()
                    }
                    if lan.isReceiving {
                        ProgressView(value: lan.totalReceiveBytes > 0 ? Double(lan.receivedBytes) / Double(lan.totalReceiveBytes) : 0)
                        HStack(spacing: 6) {
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(clamping: lan.receivedBytes), countStyle: .file)) received")
                                .font(.system(size: 9, design: .monospaced))
                            Spacer()
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(max(0, lan.receiveSpeedBytesPerSecond)), countStyle: .file))/s")
                                .font(.system(size: 9, design: .monospaced))
                        }
                        HStack {
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(clamping: lan.totalReceiveBytes > lan.receivedBytes ? lan.totalReceiveBytes - lan.receivedBytes : 0), countStyle: .file)) remaining")
                                .font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                            Spacer()
                            Button("Cancel") { lan.cancelTransfer() }
                        }.font(.system(size: 9)).foregroundStyle(.secondary)
                    } else if lan.isTransferring {
                        ProgressView(value: lan.progress)
                        HStack(spacing: 6) {
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(clamping: lan.transferredBytes), countStyle: .file)) transferred")
                                .font(.system(size: 9, design: .monospaced))
                            Spacer()
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(max(0, lan.transferSpeedBytesPerSecond)), countStyle: .file))/s")
                                .font(.system(size: 9, design: .monospaced))
                        }
                        HStack(spacing: 6) {
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(clamping: lan.remainingTransferBytes), countStyle: .file)) remaining")
                                .font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                            Spacer()
                            Text(lan.remainingTransferTimeLabel)
                                .font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                        }
                        HStack {
                            Text(lan.status).lineLimit(1)
                            Spacer()
                            Button("Cancel") { lan.cancelTransfer() }.disabled(!lan.canCancelTransfer)
                        }.font(.system(size: 9)).foregroundStyle(.secondary)
                    } else {
                        HStack(spacing: 3) {
                            ForEach(["Files", "Photos", "Audio", "Apps", "Text"], id: \.self) { category in
                                Button(category) { selectedCategory = category }
                                    .font(.system(size: 9, weight: selectedCategory == category ? .semibold : .regular))
                                    .buttonStyle(.plain)
                                    .foregroundStyle(selectedCategory == category ? .primary : .secondary)
                                    .padding(.horizontal, 6).padding(.vertical, 4)
                                    .background(selectedCategory == category ? Color.primary.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: 7))
                            }
                        }
                        VStack(spacing: 6) {
                            Image(systemName: selectedCategory == "Text" ? "text.alignleft" : "tray.and.arrow.down").font(.system(size: 24)).foregroundStyle(.secondary)
                            Text(selectedCategory == "Text" ? "Paste copied text with ⌘V" : "Drop items here or choose files").font(.system(size: 11, weight: .medium))
                            Text("Manual transfer · nothing is synced automatically").font(.system(size: 9)).foregroundStyle(.tertiary)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 13)
                        .contentShape(RoundedRectangle(cornerRadius: 11))
                        .contextMenu {
                            Button {
                                let text = NSPasteboard.general.string(forType: .string)
                                guard let text, !text.isEmpty else {
                                    dropError = "There is no text on the clipboard."
                                    return
                                }
                                let url = FileManager.default.temporaryDirectory.appendingPathComponent("AirBridge-Text-\(UUID().uuidString).txt")
                                do {
                                    try Data(text.utf8).write(to: url, options: .atomic)
                                    selectedURLs.append(url)
                                    selectedCategory = "Text"
                                    dropError = nil
                                } catch { dropError = error.localizedDescription }
                            } label: {
                                Label("Paste text", systemImage: "doc.on.clipboard")
                            }
                        }
                        .onPasteCommand(of: [.plainText]) { providers in
                            for provider in providers {
                                _ = provider.loadObject(ofClass: String.self) { value, _ in
                                    guard let value, !value.isEmpty else { return }
                                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("AirBridge-Text-\(UUID().uuidString).txt")
                                    do {
                                        try value.data(using: .utf8)?.write(to: url, options: .atomic)
                                        DispatchQueue.main.async { selectedURLs.append(url) }
                                    } catch { DispatchQueue.main.async { dropError = error.localizedDescription } }
                                }
                            }
                        }
                    }
                    if !selectedURLs.isEmpty {
                        Divider()
                        ForEach(Array(selectedURLs.enumerated()), id: \.offset) { index, url in
                            HStack(spacing: 7) {
                                Image(systemName: iconFor(url)).foregroundStyle(.secondary)
                                Text(url.lastPathComponent).font(.system(size: 10)).lineLimit(1)
                                Spacer()
                                Text(ByteCountFormatter.string(fromByteCount: (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0, countStyle: .file))
                                    .font(.system(size: 9, design: .monospaced)).foregroundStyle(.tertiary)
                                Button { selectedURLs.remove(at: index) } label: {
                                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                    if let error = dropError ?? lan.error {
                        Text(error).font(.system(size: 9)).foregroundStyle(.red).lineLimit(2)
                    }
                }
                .padding(11).background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 13))
            } else {
                VStack(spacing: 9) {
                    HStack {
                        Image(systemName: mtp.isConnected ? "cable.connector" : "cable.connector.slash")
                        Text(mtp.isConnected ? "USB · MTP connected" : "Waiting for USB connection")
                        Spacer()
                        Button { mtp.refresh() } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(.plain)
                    }.font(.system(size: 11, weight: .medium))
                    if mtp.isConnected {
                        Text("\(mtp.files.count) phone files available").font(.system(size: 10)).foregroundStyle(.secondary)
                        Button("Select files to send") { showingMTPImporter = true }.buttonStyle(.bordered)
                        ForEach(mtp.files.prefix(5)) { file in
                            HStack {
                                Text(file.name).font(.system(size: 10)).lineLimit(1)
                                Spacer()
                                Button { mtp.download(file) } label: { Image(systemName: "arrow.down.to.line") }.buttonStyle(.plain)
                            }
                        }
                    } else {
                        Text("Unlock your phone, connect by USB and choose File Transfer.").font(.system(size: 10)).foregroundStyle(.secondary)
                    }
                    if !mtp.status.isEmpty { Text(mtp.status).font(.system(size: 9)).foregroundStyle(.secondary) }
                    if let error = mtp.error { Text(error).font(.system(size: 9)).foregroundStyle(.red) }
                }.padding(11).background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 13))
            }

            HStack(spacing: 8) {
                Button {
                    if selectedMode == "Wireless" { showingImporter = true }
                    else { showingMTPImporter = true }
                } label: {
                    Label("Select files", systemImage: "plus")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(selectedMode == "Wireless" && lan.isTransferring)
            }
            if selectedMode == "Wireless" {
                HStack(spacing: 8) {
                    Text(selectedURLs.isEmpty ? "Nothing selected" : "\(selectedURLs.count) items · \(ByteCountFormatter.string(fromByteCount: selectedBytes, countStyle: .file))")
                        .font(.system(size: 9, weight: .medium, design: .rounded)).foregroundStyle(.secondary)
                    Spacer()
                    Button("Cancel") { selectedURLs.removeAll(); dropError = nil }
                        .buttonStyle(.plain).font(.system(size: 10)).disabled(selectedURLs.isEmpty || lan.isTransferring)
                    Button("Send") {
                        guard let peer = lan.peers.first else { return }
                        lan.send(selectedURLs, to: peer)
                        selectedURLs.removeAll()
                    }
                    .buttonStyle(.borderedProminent).controlSize(.small)
                    .disabled(selectedURLs.isEmpty || lan.peers.isEmpty || lan.isTransferring)
                }
            } else {
                HStack {
                    Text("USB MTP · NO ADB").font(.system(size: 8, weight: .semibold)).tracking(0.7).foregroundStyle(.tertiary)
                    Spacer()
                    Text("AIRBRIDGE").font(.system(size: 8, weight: .semibold)).tracking(0.7).foregroundStyle(.tertiary)
                }
            }
            if selectedMode == "Wireless" {
                Text("LOCAL NETWORK · NOT ENCRYPTED")
                    .font(.system(size: 8, weight: .semibold)).tracking(0.7).foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .frame(width: 350)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.primary.opacity(0.08), lineWidth: 1))
        .preferredColorScheme(.dark)
        // The entire tray is a drop target, not just the central placeholder.
        .onDrop(of: [.fileURL], isTargeted: nil) { providers in
            for provider in providers {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    guard let url else { return }
                    DispatchQueue.main.async { selectedURLs.append(url) }
                }
            }
            return true
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: selectedTypes, allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): selectedURLs.append(contentsOf: urls)
            case .failure(let failure): dropError = failure.localizedDescription
            }
        }
        .fileImporter(isPresented: $showingMTPImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): mtp.upload(urls)
            case .failure(let failure): mtp.error = failure.localizedDescription
            }
        }
        .onAppear { lan.start() }
        .alert("A file named \(lan.duplicateFilename) already exists", isPresented: $lan.showDuplicateAlert) {
            Button("Replace", role: .destructive) {
                lan.resolveDuplicate(choice: .replace)
            }
            Button("Keep Both") {
                lan.resolveDuplicate(choice: .keepBoth)
            }
            Button("Cancel Transfer", role: .cancel) {
                lan.resolveDuplicate(choice: .cancel)
            }
        } message: {
            Text("There is already a file with this name in Downloads. Replace it, save this one with a new name, or cancel the transfer.")
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                if !Task.isCancelled && selectedMode == "Wired" && !mtp.isTransferring && !mtp.isLoading { mtp.refresh() }
            }
        }
    }

    private var selectedTypes: [UTType] {
        switch selectedCategory {
        case "Photos": return [.image]
        case "Audio": return [.audio]
        case "Apps": return [UTType(filenameExtension: "app") ?? .applicationBundle, .applicationBundle]
        case "Text": return [.plainText]
        default: return [.item]
        }
    }

    private func modeButton(_ title: String, icon: String) -> some View {
        Button { selectedMode = title; if title == "Wired" { mtp.refresh() } } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                Text(title)
            }
            .font(.system(size: 10, weight: .medium))
            .frame(maxWidth: .infinity).padding(.vertical, 7)
            .background(selectedMode == title ? Color.primary.opacity(0.13) : Color.clear, in: RoundedRectangle(cornerRadius: 9))
        }.buttonStyle(.plain)
    }

    private func iconFor(_ url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        if ["jpg","jpeg","png","heic","webp","gif"].contains(ext) { return "photo" }
        if ["mp3","m4a","wav","flac","aac","ogg"].contains(ext) { return "waveform" }
        if ["apk","app","dmg","pkg"].contains(ext) { return "app" }
        if ext == "txt" { return "text.alignleft" }
        return "doc"
    }
}

@MainActor
final class MTPController: ObservableObject {
    @Published var files: [MTPFile] = []
    @Published var selection: Set<UInt32> = []
    @Published var deviceName = "Android device"
    @Published var isConnected = false
    @Published var isLoading = false
    @Published var isTransferring = false
    @Published var status = ""
    @Published var error: String?

    func refresh() {
        guard !isLoading && !isTransferring else { return }
        isLoading = true
        error = nil
        Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) {
                    try MTPService.listFiles()
                }.value
                files = result.files
                deviceName = result.deviceName
                isConnected = true
                status = "Connected · \(result.files.count) files indexed"
            } catch {
                files = []
                isConnected = false
                self.error = error.localizedDescription
            }
            isLoading = false
        }
    }

    func upload(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        isTransferring = true
        error = nil
        status = "Preparing upload…"
        Task {
            do {
                for url in urls {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    status = "Sending \(url.lastPathComponent)…"
                    try await Task.detached(priority: .userInitiated) {
                        try MTPService.upload(url)
                    }.value
                }
                status = "Sent \(urls.count) file(s) to phone storage"
                await reloadAfterTransfer()
            } catch {
                self.error = error.localizedDescription
            }
            isTransferring = false
        }
    }

    func download(_ file: MTPFile) {
        guard !isTransferring else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = file.name
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        isTransferring = true
        error = nil
        status = "Downloading \(file.name)…"
        Task {
            do {
                try await Task.detached(priority: .userInitiated) {
                    try MTPService.download(file, to: destination)
                }.value
                status = "Saved \(file.name)"
            } catch {
                self.error = error.localizedDescription
            }
            isTransferring = false
        }
    }

    private func reloadAfterTransfer() async {
        do {
            let result = try await Task.detached(priority: .userInitiated) {
                try MTPService.listFiles()
            }.value
            files = result.files
            deviceName = result.deviceName
            isConnected = true
        } catch {
            self.error = error.localizedDescription
            isConnected = false
        }
    }
}

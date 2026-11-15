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
        AirBridgeTrayWindow.shared.show(statusItem: statusItem)
    }
}

@MainActor
final class AirBridgeTrayWindow: NSObject, NSWindowDelegate {
    static let shared = AirBridgeTrayWindow()
    private var window: NSWindow?

    func show(statusItem: NSStatusItem? = nil) {
        if let window, window.isVisible || window.isMiniaturized {
            if window.isMiniaturized { window.deminiaturize(nil) }
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            return
        }
        // A closed NSWindow must be recreated; retaining the old instance alone
        // can leave the menu-bar command pointing at an invisible window.
        window = nil

        let screen = statusItem?.button?.window?.screen ?? NSScreen.main ?? NSScreen.screens.first
        let visibleFrame = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
        let trayHeight: CGFloat = 380
        let trayWidth: CGFloat = 380
        let tray = AirBridgePopover()
        let hosting = NSHostingController(rootView: tray)
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: trayWidth, height: trayHeight),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = false
        panel.worksWhenModal = true
        panel.animationBehavior = .utilityWindow
        panel.title = "AirBridge Drop Tray"
        // Keep the native title bar visible: it supplies reliable window
        // dragging and the standard macOS close/minimize controls.
        panel.titlebarAppearsTransparent = false
        panel.titleVisibility = .visible
        panel.isMovable = true
        panel.isMovableByWindowBackground = true
        panel.isReleasedWhenClosed = false
        panel.setFrameAutosaveName("AirBridgeDropTray")
        panel.delegate = self
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.contentViewController = hosting
        panel.minSize = NSSize(width: trayWidth, height: trayHeight)
        panel.maxSize = NSSize(width: trayWidth, height: trayHeight)
        if let button = statusItem?.button, let buttonWindow = button.window {
            let buttonRect = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
            let x = min(max(buttonRect.midX - trayWidth / 2, visibleFrame.minX + 8), visibleFrame.maxX - trayWidth - 8)
            let y = min(max(buttonRect.minY - trayHeight - 8, visibleFrame.minY + 8), visibleFrame.maxY - trayHeight - 8)
            panel.setFrameOrigin(NSPoint(x: x, y: y))
        } else {
            panel.center()
        }
        window = panel

        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
    }

    func close() {
        window?.performClose(nil)
    }

    func windowWillClose(_ notification: Notification) {
        guard let closedWindow = notification.object as? NSWindow,
              closedWindow === window else { return }
        window = nil
    }

    func windowDidChangeScreen(_ notification: Notification) {
        guard let movedWindow = notification.object as? NSWindow,
              movedWindow === window,
              let visible = movedWindow.screen?.visibleFrame else { return }
        var frame = movedWindow.frame
        frame.size.width = min(frame.width, max(320, visible.width - 16))
        frame.size.height = min(frame.height, max(300, visible.height - 32))
        frame.origin.x = min(max(frame.origin.x, visible.minX + 8), visible.maxX - frame.width - 8)
        frame.origin.y = min(max(frame.origin.y, visible.minY + 8), visible.maxY - frame.height - 8)
        movedWindow.setFrame(frame, display: true, animate: false)
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
        VStack(spacing: 9) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.up.arrow.down.square.fill").font(.system(size: 19))
                VStack(alignment: .leading, spacing: 2) {
                    Text("AirBridge").font(.system(size: 15, weight: .semibold))
                    Text(lan.peers.isEmpty ? "Searching for your phone…" : "Connected · Moto Edge 50 Neo")
                        .font(.system(size: 9)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                modeButton("Wireless", icon: "wifi")
                modeButton("Wired", icon: "cable.connector").frame(maxWidth: 76)
                Button { AirBridgeTrayWindow.shared.close() } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .semibold))
                        .frame(width: 22, height: 22)
                        .background(Color.primary.opacity(0.09), in: Circle())
                }.buttonStyle(.plain).help("Close Drop Tray")
            }
            if selectedMode == "Wireless" {
                ZStack {
                    RoundedRectangle(cornerRadius: 16).fill(Color.primary.opacity(0.045))
                    RoundedRectangle(cornerRadius: 16)
                        .strokeBorder(Color.primary.opacity(0.12), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
                    VStack(spacing: 8) {
                        Image(systemName: "tray.and.arrow.down.fill").font(.system(size: 31, weight: .light)).foregroundStyle(.secondary)
                        Text(selectedURLs.isEmpty ? "Drop files here" : "\(selectedURLs.count) item(s) ready")
                            .font(.system(size: 13, weight: .medium))
                        Text(selectedURLs.isEmpty ? "Drag files from anywhere on your Mac" : ByteCountFormatter.string(fromByteCount: selectedBytes, countStyle: .file))
                            .font(.system(size: 10)).foregroundStyle(.secondary)
                        if !selectedURLs.isEmpty {
                            Text(selectedURLs.prefix(2).map(\.lastPathComponent).joined(separator: " · "))
                                .font(.system(size: 9)).foregroundStyle(.tertiary).lineLimit(1)
                        }
                        if lan.isReceiving {
                            ProgressView(value: lan.totalReceiveBytes > 0 ? Double(lan.receivedBytes) / Double(lan.totalReceiveBytes) : 0).frame(maxWidth: 190)
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(clamping: lan.receivedBytes), countStyle: .file)) received")
                                .font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                        } else if lan.isTransferring {
                            ProgressView(value: lan.progress).frame(maxWidth: 190)
                            Text("\(ByteCountFormatter.string(fromByteCount: Int64(clamping: lan.transferredBytes), countStyle: .file)) sent · \(ByteCountFormatter.string(fromByteCount: Int64(max(0, lan.transferSpeedBytesPerSecond)), countStyle: .file))/s")
                                .font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
                            Button("Cancel transfer") { lan.cancelTransfer() }.font(.system(size: 9))
                        }
                    }.padding(12)
                }
                .frame(height: 190)
                .onDrop(of: [.fileURL], isTargeted: nil) { providers in
                    for provider in providers {
                        _ = provider.loadObject(ofClass: URL.self) { url, _ in
                            guard let url else { return }
                            DispatchQueue.main.async { if !selectedURLs.contains(url) { selectedURLs.append(url) } }
                        }
                    }
                    return true
                }
            } else {
                VStack(spacing: 8) {
                    Image(systemName: mtp.isConnected ? "cable.connector" : "cable.connector.slash")
                        .font(.system(size: 27)).foregroundStyle(.secondary)
                    Text(mtp.isConnected ? "USB · MTP connected" : "Waiting for USB connection").font(.system(size: 12, weight: .medium))
                    Text(mtp.isConnected ? "\(mtp.files.count) phone files available" : "Unlock your phone and choose File Transfer")
                        .font(.system(size: 10)).foregroundStyle(.secondary)
                    Button("Refresh USB") { mtp.refresh() }.buttonStyle(.bordered)
                    if mtp.isConnected {
                        Button("Send selected files to phone") { showingMTPImporter = true }.buttonStyle(.borderedProminent)
                    }
                    if let error = mtp.error { Text(error).font(.system(size: 9)).foregroundStyle(.red).lineLimit(2) }
                }
                .frame(maxWidth: .infinity).frame(height: 190)
                .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 16))
            }
            if selectedMode == "Wireless" {
                HStack(spacing: 8) {
                    Button { showingImporter = true } label: {
                        Label("Choose files", systemImage: "plus").frame(maxWidth: .infinity)
                    }.buttonStyle(.bordered).disabled(lan.isTransferring)
                    Button {
                        guard let peer = lan.peers.first else { return }
                        lan.send(selectedURLs, to: peer)
                        selectedURLs.removeAll()
                    } label: {
                        Label("Send", systemImage: "arrow.up").frame(maxWidth: .infinity)
                    }.buttonStyle(.borderedProminent)
                        .disabled(selectedURLs.isEmpty || lan.peers.isEmpty || lan.isTransferring)
                }
                HStack(spacing: 6) {
                    Text(lan.peers.isEmpty ? "Phone not found · same hotspot required" : "Phone ready")
                    Spacer()
                    if !selectedURLs.isEmpty { Button("Clear") { selectedURLs.removeAll() }.buttonStyle(.plain) }
                }.font(.system(size: 9)).foregroundStyle(.secondary)
                if let error = dropError ?? lan.error {
                    Text(error).font(.system(size: 9)).foregroundStyle(.red).lineLimit(1)
                }
            } else {
                HStack {
                    Button("Choose files to send") { showingMTPImporter = true }.buttonStyle(.bordered)
                    Spacer()
                    Text("USB MTP").font(.system(size: 8, weight: .semibold)).foregroundStyle(.tertiary)
                }
            }
        }
        .padding(12)
        .frame(width: 356, height: 336)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.primary.opacity(0.08), lineWidth: 1))
        .preferredColorScheme(.dark)
        .onDrop(of: [.fileURL], isTargeted: nil) { providers in
            for provider in providers {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    guard let url else { return }
                    DispatchQueue.main.async { if !selectedURLs.contains(url) { selectedURLs.append(url) } }
                }
            }
            return true
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): selectedURLs.append(contentsOf: urls.filter { !selectedURLs.contains($0) })
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
            Button("Replace", role: .destructive) { lan.resolveDuplicate(choice: .replace) }
            Button("Keep Both") { lan.resolveDuplicate(choice: .keepBoth) }
            Button("Cancel Transfer", role: .cancel) { lan.resolveDuplicate(choice: .cancel) }
        } message: {
            Text("There is already a file with this name in Downloads. Replace it, save this one with a new name, or cancel the transfer.")
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
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
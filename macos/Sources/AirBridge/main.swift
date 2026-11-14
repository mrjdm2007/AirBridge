import AppKit
import SwiftUI
import UniformTypeIdentifiers

@main
struct AirBridgeApp: App {
    var body: some Scene {
        MenuBarExtra("AirBridge", systemImage: "arrow.up.arrow.down.square") {
            AirBridgePopover()
        }
        .menuBarExtraStyle(.window)

        Window("AirBridge", id: "main") {
            AirBridgePopover()
                .frame(width: 520, height: 620)
        }
        .defaultSize(width: 520, height: 620)
    }
}

struct AirBridgePopover: View {
    @StateObject private var mtp = MTPController()
    @State private var showingImporter = false
    @State private var clipboardEnabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("AirBridge").font(.system(size: 23, weight: .semibold))
                    Text("Private transfers between your Mac and Android")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "iphone.and.arrow.right.inward")
                    .font(.system(size: 24, weight: .regular))
            }

            HStack(spacing: 10) {
                Image(systemName: mtp.isConnected ? "cable.connector" : "cable.connector.slash")
                    .font(.system(size: 18))
                VStack(alignment: .leading, spacing: 2) {
                    Text(mtp.isConnected ? mtp.deviceName : "No MTP device detected")
                        .font(.system(size: 13, weight: .medium))
                    Text(mtp.isConnected ? "USB · Media Transfer Protocol" : "Connect your phone and choose File Transfer")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    mtp.refresh()
                } label: {
                    if mtp.isLoading {
                        ProgressView().controlSize(.small)
                    } else {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                }
                .disabled(mtp.isLoading || mtp.isTransferring)
                .buttonStyle(.bordered)
            }
            .padding(13)
            .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 13))

            if !mtp.isConnected {
                ContentUnavailableView {
                    Label("Connect your Android", systemImage: "cable.connector")
                } description: {
                    Text("Unlock your phone, connect it with a data-capable USB cable, then select “File transfer / Android Auto” in the USB notification. Tap Refresh.")
                }
                .frame(maxWidth: .infinity, minHeight: 190)
            } else {
                HStack {
                    Text("PHONE FILES")
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(0.8).foregroundStyle(.secondary)
                    Spacer()
                    Text("\(mtp.files.count) files")
                        .font(.system(size: 10)).foregroundStyle(.tertiary)
                }

                List(selection: $mtp.selection) {
                    ForEach(mtp.files) { file in
                        HStack(spacing: 10) {
                            Image(systemName: "doc")
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(file.name).lineLimit(1)
                                    .font(.system(size: 12, weight: .medium))
                                Text(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))
                                    .font(.system(size: 10)).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button {
                                mtp.download(file)
                            } label: {
                                Image(systemName: "arrow.down.to.line")
                            }
                            .buttonStyle(.borderless)
                            .help("Save to Downloads")
                            .disabled(mtp.isTransferring)
                        }
                        .tag(file.id)
                    }
                }
                .listStyle(.inset)
                .frame(minHeight: 180)
                .overlay {
                    if mtp.files.isEmpty && !mtp.isLoading {
                        ContentUnavailableView("No files found", systemImage: "folder")
                    }
                }
            }

            HStack(spacing: 10) {
                Button {
                    showingImporter = true
                } label: {
                    Label("Send files to phone", systemImage: "arrow.up.to.line")
                }
                .buttonStyle(.borderedProminent)
                .disabled(!mtp.isConnected || mtp.isTransferring)

                Button {
                    mtp.refresh()
                } label: {
                    Label("Reload phone files", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .disabled(!mtp.isConnected || mtp.isTransferring || mtp.isLoading)
            }

            if mtp.isTransferring {
                ProgressView(mtp.status)
                    .font(.system(size: 11))
            } else if !mtp.status.isEmpty {
                Text(mtp.status)
                    .font(.system(size: 11)).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            if let error = mtp.error {
                Text(error)
                    .font(.system(size: 11)).foregroundStyle(.red)
                    .textSelection(.enabled)
            }

            Divider()

            Toggle(isOn: $clipboardEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Clipboard sync").font(.system(size: 12, weight: .medium))
                    Text("Wi-Fi clipboard sync is not implemented yet")
                        .font(.system(size: 10)).foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.switch)
            .disabled(true)

            HStack {
                Text("PRIVATE BY DESIGN")
                Spacer()
                Text("USB MTP · NO ADB")
            }
            .font(.system(size: 8, weight: .semibold, design: .rounded))
            .tracking(0.8).foregroundStyle(.tertiary)
        }
        .padding(18)
        .frame(width: 480, height: 570)
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): mtp.upload(urls)
            case .failure(let failure): mtp.error = failure.localizedDescription
            }
        }
        .onAppear { mtp.refresh() }
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

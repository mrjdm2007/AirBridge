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
                .frame(width: 420, height: 520)
        }
        .defaultSize(width: 420, height: 520)
    }
}

struct AirBridgePopover: View {
    @State private var isTargeted = false
    @State private var selectedFiles: [URL] = []
    @State private var showingImporter = false
    @State private var clipboardEnabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("AirBridge").font(.system(size: 23, weight: .semibold))
                    Text("Your devices, connected.")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "iphone.and.arrow.right.inward")
                    .font(.system(size: 24, weight: .regular))
            }

            RoundedRectangle(cornerRadius: 18)
                .fill(isTargeted ? Color.primary.opacity(0.12) : Color.primary.opacity(0.055))
                .overlay {
                    VStack(spacing: 10) {
                        Image(systemName: "arrow.down.doc")
                            .font(.system(size: 30, weight: .light))
                        Text("Drop files here")
                            .font(.system(size: 16, weight: .medium))
                        Text("Send files directly to your Android")
                            .font(.system(size: 11)).foregroundStyle(.secondary)
                        Button("Choose files") { showingImporter = true }
                            .buttonStyle(.bordered)
                    }
                    .padding(20)
                }
                .frame(height: 190)
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(isTargeted ? Color.primary.opacity(0.45) : Color.primary.opacity(0.12), style: StrokeStyle(lineWidth: 1, dash: [5, 5])))
                .onDrop(of: [UTType.fileURL], isTargeted: $isTargeted) { providers in
                    for provider in providers {
                        _ = provider.loadObject(ofClass: URL.self) { url, _ in
                            guard let url else { return }
                            DispatchQueue.main.async { selectedFiles.append(url) }
                        }
                    }
                    return true
                }

            HStack {
                Image(systemName: "iphone")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Android device").font(.system(size: 13, weight: .medium))
                    Text("Waiting for local connection").font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Circle().fill(Color.secondary.opacity(0.5)).frame(width: 7, height: 7)
                Text("Offline").font(.system(size: 11)).foregroundStyle(.secondary)
            }
            .padding(14)
            .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 13))

            Toggle(isOn: $clipboardEnabled) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Clipboard sync").font(.system(size: 13, weight: .medium))
                    Text("Mac → Android · Direct local connection")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.switch)

            if !selectedFiles.isEmpty {
                Text("\(selectedFiles.count) item(s) queued")
                    .font(.system(size: 11)).foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
            HStack {
                Text("PRIVATE BY DESIGN")
                Spacer()
                Text("NO CLOUD · DIRECT TRANSFER")
            }
            .font(.system(size: 8, weight: .semibold, design: .rounded))
            .tracking(0.8)
            .foregroundStyle(.tertiary)
        }
        .padding(20)
        .frame(width: 340, height: 470)
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { selectedFiles = urls }
        }
    }
}

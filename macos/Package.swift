// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AirBridge",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "AirBridge", targets: ["AirBridge"])],
    targets: [
        .executableTarget(name: "AirBridge", path: "Sources/AirBridge")
    ]
)

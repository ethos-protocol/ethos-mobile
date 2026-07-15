// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "EthosProtocol",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "EthosProtocol", targets: ["EthosProtocol"]),
        .library(name: "TTLWidget", targets: ["TTLWidget"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "EthosProtocol",
            path: "Sources",
            // Widget: built into TTLWidget below. App: EthosProtocolApp.swift's
            // `@main struct EthosProtocolApp: App` synthesizes a real process
            // entry point (_main); a *library* target has no business defining
            // one, and linking it into EthosProtocolTests collided with the
            // XCTest host's own entry point ("duplicate symbol '_main'"). The
            // real app entry point still gets compiled — just by the XcodeGen
            // app target (project.yml), which is the only place it belongs.
            exclude: ["Widget", "App"]
        ),
        // WidgetKit extension target — deploy as a separate app extension in Xcode.
        // Requires Associated Domains (applinks:ethos-protocol.app) entitlement and
        // BGTaskSchedulerPermittedIdentifiers in Info.plist for the host app.
        .target(
            name: "TTLWidget",
            dependencies: ["EthosProtocol"],
            path: "Sources/Widget"
        ),
        .testTarget(
            name: "EthosProtocolTests",
            dependencies: ["EthosProtocol", "TTLWidget"],
            path: "Tests"
        )
    ]
)

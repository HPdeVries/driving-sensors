// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DrivingSensors",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "DrivingSensors",
            targets: ["DrivingSensorsPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "DrivingSensorsPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/DrivingSensorsPlugin",
            resources: [.copy("PrivacyInfo.xcprivacy")],
            linkerSettings: [.linkedLibrary("sqlite3"), .linkedLibrary("z")]),
        .testTarget(
            name: "DrivingSensorsPluginTests",
            dependencies: ["DrivingSensorsPlugin"],
            path: "ios/Tests/DrivingSensorsPluginTests")
    ]
)
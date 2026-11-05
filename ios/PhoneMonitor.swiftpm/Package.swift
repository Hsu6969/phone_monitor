// swift-tools-version: 5.9

// iPad camera monitor. Open this folder (PhoneMonitor.swiftpm) in Swift Playgrounds
// on the iPad and tap Run; no Mac or developer account needed.

import PackageDescription
import AppleProductTypes

let package = Package(
    name: "PhoneMonitor",
    platforms: [
        .iOS("17.0")
    ],
    products: [
        .iOSApplication(
            name: "PhoneMonitor",
            targets: ["AppModule"],
            bundleIdentifier: "com.phonemonitor.ipad",
            teamIdentifier: "",
            displayVersion: "0.1.0",
            bundleVersion: "1",
            appIcon: .placeholder(icon: .camera),
            accentColor: .presetColor(.blue),
            supportedDeviceFamilies: [
                .pad
            ],
            supportedInterfaceOrientations: [
                .landscapeRight,
                .landscapeLeft,
                .portrait,
                .portraitUpsideDown(.when(deviceFamilies: [.pad]))
            ],
            capabilities: [
                .camera(purposeString: "用來接收外接相機或 HDMI 擷取卡的畫面。本 App 不會使用 iPad 本身的鏡頭。")
            ],
            appCategory: .photography
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            path: "."
        )
    ]
)

import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var camera = CameraMonitor()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showBar = true
    @State private var showModes = false
    @State private var showInfo = false
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            CameraPreview(
                session: camera.session,
                scaleMode: camera.scaleMode,
                streamToken: camera.currentMode?.id ?? ""
            )
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .onTapGesture { toggleBar() }

            if camera.state == .streaming && camera.isFlat {
                Text("相機目前沒有輸出影像\n（USB 串流模式下，相機顯示選單或回放時會暫停影像；若用 HDMI 擷取卡，請確認相機 HDMI 有輸出）")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding()
                    .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                    .padding(32)
                    .allowsHitTesting(false)
            }

            if let message {
                messagePanel(message)
            }

            if showBar && camera.state == .streaming {
                VStack {
                    topBar
                    Spacer()
                }
                .padding(8)
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            camera.activate()
            scheduleHide()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: camera.activate()
            case .background: camera.deactivate()
            default: break
            }
        }
        .onChange(of: camera.state) { _, state in
            if state == .streaming {
                showBar = true
                scheduleHide()
            }
        }
        .sheet(isPresented: $showModes) { modePicker }
        .alert("裝置資訊", isPresented: $showInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(camera.infoText)
        }
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(camera.deviceName)
                if let mode = camera.currentMode {
                    Text("\(mode.width)×\(mode.height) \(mode.codec) · \(String(format: "%.1f", camera.displayFps)) fps")
                }
            }
            .font(.footnote)
            .foregroundStyle(.white)
            .lineLimit(1)
            Spacer()
            Button("資訊") { showInfo = true; scheduleHide() }
            Button("解析度") { showModes = true; scheduleHide() }
            Button(camera.scaleMode == .fit ? "完整顯示" : "填滿畫面") {
                camera.scaleMode = camera.scaleMode == .fit ? .fill : .fit
                scheduleHide()
            }
        }
        .buttonStyle(.borderless)
        .tint(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
    }

    private var modePicker: some View {
        NavigationStack {
            List(camera.modes) { mode in
                Button {
                    camera.select(mode)
                    showModes = false
                } label: {
                    HStack {
                        Text(mode.label).foregroundStyle(.primary)
                        Spacer()
                        if mode.id == camera.currentMode?.id {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                }
            }
            .navigationTitle("選擇解析度")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("關閉") { showModes = false }
                }
            }
        }
    }

    // MARK: - Messages

    private struct Message {
        let title: String
        let body: String
        var showRetry = true
        var showSettings = false
    }

    private var message: Message? {
        switch camera.state {
        case .streaming:
            return nil
        case .needPermission:
            return Message(
                title: "需要「相機」權限",
                body: "iPadOS 規定讀取外接相機 / 擷取卡必須允許「相機」權限。本 App 不會使用 iPad 本身的鏡頭。",
                showRetry: false
            )
        case .permissionDenied:
            return Message(
                title: "相機權限被拒絕",
                body: "沒有「相機」權限就無法讀取外接影像裝置。請按「開啟設定」允許相機權限，再回到 App。",
                showSettings: true
            )
        case .noDevice, .disconnected:
            return Message(
                title: camera.state == .disconnected ? "裝置已中斷連線" : "請連接相機",
                body: """
                把影像裝置接到 iPad 的 USB-C 孔後會自動顯示畫面。

                • 想看到相機的完整螢幕（拍攝、回放、選單）：
                  相機 HDMI 輸出 → HDMI 擷取卡（UVC）→ iPad USB-C

                • 只需要即時取景：
                  把相機的 USB 連線模式設成「網路攝影機 / UVC / USB 串流」，再用 USB-C 線直接連 iPad

                （需要 USB-C 接口的 iPad 與 iPadOS 17 以上）
                """
            )
        case .connecting(let name):
            return Message(title: "連線中…", body: name, showRetry: false)
        case .interrupted(let reason):
            return Message(title: "影像暫停", body: reason)
        case .error(let text):
            return Message(title: "無法開啟影像", body: text)
        }
    }

    private func messagePanel(_ m: Message) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(m.title).font(.title3.bold())
            Text(m.body).font(.callout).opacity(0.9)
            HStack {
                Spacer()
                if m.showSettings {
                    Button("開啟設定") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .buttonStyle(.bordered)
                }
                if m.showRetry {
                    Button("重新偵測") { camera.retry() }
                        .buttonStyle(.bordered)
                }
            }
        }
        .foregroundStyle(.white)
        .tint(.white)
        .padding(20)
        .frame(maxWidth: 560)
        .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 12))
        .padding(24)
    }

    // MARK: - Bar visibility

    private func toggleBar() {
        guard camera.state == .streaming else { return }
        showBar.toggle()
        if showBar { scheduleHide() }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { showBar = false }
        }
    }
}

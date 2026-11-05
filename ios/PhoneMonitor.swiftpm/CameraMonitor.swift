@preconcurrency import AVFoundation
import SwiftUI
import UIKit

/// One selectable capture mode: format (resolution + pixel format) and frame rate.
struct VideoMode: Identifiable {
    let id: String
    let format: AVCaptureDevice.Format
    let width: Int
    let height: Int
    let frameDuration: CMTime
    let codec: String

    var fps: Double { frameDuration.seconds > 0 ? 1.0 / frameDuration.seconds : 0 }
    var fpsLabel: String {
        abs(fps - fps.rounded()) < 0.05 ? String(format: "%.0f", fps) : String(format: "%.2f", fps)
    }
    var label: String { "\(width)×\(height)  \(fpsLabel)fps  \(codec)" }
}

enum ScaleMode: String {
    case fit, fill
}

enum CameraError: LocalizedError {
    case cannotAddInput
    var errorDescription: String? {
        switch self {
        case .cannotAddInput: return "無法使用這個影像裝置（可能被其他 App 使用中）"
        }
    }
}

/// Finds the external USB video device (camera in UVC/webcam mode or HDMI capture card),
/// runs the capture session and publishes state for the UI.
///
/// iPadOS 17+ supports UVC devices natively through AVFoundation (iPad with USB-C only),
/// so no USB code is needed here.
@MainActor
final class CameraMonitor: ObservableObject {
    enum State: Equatable {
        case needPermission
        case permissionDenied
        case noDevice
        case disconnected
        case connecting(String)
        case streaming
        case interrupted(String)
        case error(String)
    }

    @Published private(set) var state: State = .noDevice
    @Published private(set) var deviceName = ""
    @Published private(set) var modes: [VideoMode] = []
    @Published private(set) var currentMode: VideoMode?
    @Published private(set) var displayFps: Double = 0
    @Published private(set) var isFlat = false
    @Published var scaleMode: ScaleMode {
        didSet { UserDefaults.standard.set(scaleMode.rawValue, forKey: "scaleMode") }
    }

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "camera.session")
    private let frameQueue = DispatchQueue(label: "camera.frames")
    private let output = AVCaptureVideoDataOutput()
    private let analyzer = FrameAnalyzer()
    private var device: AVCaptureDevice?
    private var observers: [NSObjectProtocol] = []
    private var active = false

    init() {
        scaleMode = ScaleMode(rawValue: UserDefaults.standard.string(forKey: "scaleMode") ?? "") ?? .fit

        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.setSampleBufferDelegate(analyzer, queue: frameQueue)
        analyzer.onFps = { [weak self] fps in
            Task { @MainActor in self?.displayFps = fps }
        }
        analyzer.onFlat = { [weak self] flat in
            Task { @MainActor in self?.isFlat = flat }
        }

        let session = self.session
        sessionQueue.async {
            // Allow the camera to keep running in Split View / Stage Manager where supported.
            if session.isMultitaskingCameraAccessSupported {
                session.isMultitaskingCameraAccessEnabled = true
            }
        }
        observeNotifications()
    }

    // MARK: - Lifecycle

    func activate() {
        active = true
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            connectIfPossible()
        case .notDetermined:
            state = .needPermission
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if granted {
                        self.connectIfPossible()
                    } else {
                        self.state = .permissionDenied
                    }
                }
            }
        default:
            state = .permissionDenied
        }
    }

    func deactivate() {
        active = false
        stop()
    }

    func retry() {
        stop()
        activate()
    }

    func select(_ mode: VideoMode) {
        guard let device else { return }
        let session = self.session
        sessionQueue.async {
            session.beginConfiguration()
            let ok = (try? Self.apply(mode, to: device)) != nil
            session.commitConfiguration()
            Task { @MainActor [weak self] in
                guard let self else { return }
                if ok {
                    self.currentMode = mode
                    UserDefaults.standard.set(mode.id, forKey: Self.modeKey(device))
                } else {
                    self.state = .error("裝置不接受此解析度（\(mode.label)）")
                }
            }
        }
    }

    // MARK: - Device handling

    private static func externalDevices() -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [.external], mediaType: .video, position: .unspecified
        ).devices
    }

    private func connectIfPossible() {
        guard active, device == nil else { return }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return }
        guard let dev = Self.externalDevices().first else {
            if state != .disconnected { state = .noDevice }
            return
        }
        start(dev)
    }

    private func start(_ dev: AVCaptureDevice) {
        device = dev
        deviceName = dev.localizedName
        state = .connecting(dev.localizedName)
        let modes = Self.buildModes(dev)
        self.modes = modes
        let saved = UserDefaults.standard.string(forKey: Self.modeKey(dev))
        let mode = modes.first { $0.id == saved } ?? Self.defaultMode(modes)
        frameQueue.async { [analyzer] in analyzer.reset() }
        isFlat = false

        let session = self.session
        let output = self.output
        sessionQueue.async {
            do {
                let input = try AVCaptureDeviceInput(device: dev)
                session.beginConfiguration()
                for old in session.inputs { session.removeInput(old) }
                guard session.canAddInput(input) else {
                    session.commitConfiguration()
                    throw CameraError.cannotAddInput
                }
                session.addInput(input)
                if !session.outputs.contains(output), session.canAddOutput(output) {
                    session.addOutput(output)
                }
                if let mode { try? Self.apply(mode, to: dev) }
                session.commitConfiguration()
                session.startRunning()
                Task { @MainActor [weak self] in
                    guard let self, self.device === dev else { return }
                    self.currentMode = mode
                    self.state = .streaming
                }
            } catch {
                Task { @MainActor [weak self] in
                    guard let self, self.device === dev else { return }
                    self.device = nil
                    self.state = .error(error.localizedDescription)
                }
            }
        }
    }

    private func stop() {
        device = nil
        currentMode = nil
        isFlat = false
        displayFps = 0
        let session = self.session
        sessionQueue.async {
            if session.isRunning { session.stopRunning() }
            session.beginConfiguration()
            for input in session.inputs { session.removeInput(input) }
            session.commitConfiguration()
        }
    }

    /// Must be called on the session queue (inside begin/commitConfiguration when running).
    nonisolated private static func apply(_ mode: VideoMode, to dev: AVCaptureDevice) throws {
        try dev.lockForConfiguration()
        defer { dev.unlockForConfiguration() }
        dev.activeFormat = mode.format
        // Setting a duration outside the supported ranges raises an Objective-C exception,
        // so only set it when a range contains it.
        let supported = mode.format.videoSupportedFrameRateRanges.contains { r in
            CMTimeCompare(mode.frameDuration, r.minFrameDuration) >= 0 &&
                CMTimeCompare(mode.frameDuration, r.maxFrameDuration) <= 0
        }
        if supported {
            dev.activeVideoMinFrameDuration = mode.frameDuration
            dev.activeVideoMaxFrameDuration = mode.frameDuration
        }
    }

    private static func modeKey(_ dev: AVCaptureDevice) -> String { "mode_\(dev.modelID)" }

    private static func codecName(_ code: FourCharCode) -> String {
        let chars = [24, 16, 8, 0].map { Character(UnicodeScalar(UInt8((code >> $0) & 0xFF))) }
        let fourcc = String(chars)
        switch fourcc {
        case "420v", "420f": return "YUV420"
        case "yuvs", "2vuy": return "YUV422"
        case "jpeg", "dmb1": return "MJPEG"
        default: return fourcc
        }
    }

    private static func buildModes(_ dev: AVCaptureDevice) -> [VideoMode] {
        var seen = Set<String>()
        var result: [VideoMode] = []
        for format in dev.formats {
            let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let codec = codecName(CMFormatDescriptionGetMediaSubType(format.formatDescription))
            for range in format.videoSupportedFrameRateRanges {
                var durations = [range.minFrameDuration]
                if CMTimeCompare(range.maxFrameDuration, range.minFrameDuration) != 0 {
                    for fps in [60, 50, 30, 25, 24, 15] {
                        let d = CMTime(value: 1, timescale: CMTimeScale(fps))
                        if CMTimeCompare(d, range.minFrameDuration) >= 0 &&
                            CMTimeCompare(d, range.maxFrameDuration) <= 0 {
                            durations.append(d)
                        }
                    }
                }
                for d in durations where d.seconds > 0 {
                    let id = "\(dims.width)x\(dims.height)@\(String(format: "%.2f", 1.0 / d.seconds))-\(codec)"
                    if seen.insert(id).inserted {
                        result.append(VideoMode(
                            id: id, format: format, width: Int(dims.width), height: Int(dims.height),
                            frameDuration: d, codec: codec
                        ))
                    }
                }
            }
        }
        return result.sorted { ($0.width * $0.height, $0.fps) > ($1.width * $1.height, $1.fps) }
    }

    /// Prefers 1920x1080 at the fastest rate up to 60 fps.
    private static func defaultMode(_ modes: [VideoMode]) -> VideoMode? {
        let pool = modes.filter { $0.fps <= 60.5 }.isEmpty ? modes : modes.filter { $0.fps <= 60.5 }
        if let fhd = pool.filter({ $0.width == 1920 && $0.height == 1080 }).max(by: { $0.fps < $1.fps }) {
            return fhd
        }
        let maxArea = pool.filter { $0.width * $0.height <= 1920 * 1080 }.map { $0.width * $0.height }.max()
        if let maxArea {
            return pool.filter { $0.width * $0.height == maxArea }.max(by: { $0.fps < $1.fps })
        }
        return pool.first
    }

    // MARK: - Info

    var infoText: String {
        guard let dev = device else { return "沒有連接裝置" }
        var lines = [
            dev.localizedName,
            "製造商：\(dev.manufacturer)",
            "型號：\(dev.modelID)",
            "",
            "目前模式：\(currentMode?.label ?? "-")",
            "",
            "影像模式：",
        ]
        lines += modes.map { "  \($0.label)" }
        lines += ["", "（iPadOS 不提供外接裝置的快門 / 光圈 / ISO 資訊；改用 HDMI 擷取卡時，相機螢幕上的參數會直接出現在畫面中。）"]
        return lines.joined(separator: "\n")
    }

    // MARK: - Notifications

    private func observeNotifications() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVCaptureDevice.wasConnectedNotification, object: nil, queue: .main) { [weak self] note in
            guard let dev = note.object as? AVCaptureDevice, dev.deviceType == .external else { return }
            MainActor.assumeIsolated {
                guard let self else { return }
                if self.state == .disconnected { self.state = .noDevice }
                self.connectIfPossible()
            }
        })
        observers.append(nc.addObserver(forName: AVCaptureDevice.wasDisconnectedNotification, object: nil, queue: .main) { [weak self] note in
            let dev = note.object as? AVCaptureDevice
            MainActor.assumeIsolated {
                guard let self, let dev, dev === self.device else { return }
                self.stop()
                self.state = .disconnected
                // Another external device may still be attached.
                self.connectIfPossible()
            }
        })
        observers.append(nc.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int
            let reason = raw.flatMap { AVCaptureSession.InterruptionReason(rawValue: $0) }
            MainActor.assumeIsolated {
                guard let self, self.device != nil else { return }
                switch reason {
                case .videoDeviceNotAvailableWithMultipleForegroundApps:
                    self.state = .interrupted("分割畫面 / 多工時無法使用相機，請讓 App 全螢幕顯示。")
                case .videoDeviceInUseByAnotherClient:
                    self.state = .interrupted("影像裝置正被其他 App 使用中。")
                default:
                    self.state = .interrupted("影像暫停中…")
                }
            }
        })
        observers.append(nc.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.device != nil else { return }
                self.state = .streaming
            }
        })
        observers.append(nc.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? Error
            MainActor.assumeIsolated {
                guard let self, self.active else { return }
                self.stop()
                self.state = .error(error?.localizedDescription ?? "影像串流發生錯誤")
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                    MainActor.assumeIsolated { self?.connectIfPossible() }
                }
            }
        })
    }
}

/// Measures the delivered frame rate and detects single-color frames
/// (e.g. Nikon sends a gray picture while its menu is open).
final class FrameAnalyzer: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onFps: ((Double) -> Void)?
    var onFlat: ((Bool) -> Void)?

    private var frameCount = 0
    private var windowStart = CACurrentMediaTime()
    private var counter = 0
    private var flatCount = 0
    private var flatReported = false

    func reset() {
        frameCount = 0
        windowStart = CACurrentMediaTime()
        flatCount = 0
        flatReported = false
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        frameCount += 1
        let now = CACurrentMediaTime()
        if now - windowStart >= 1 {
            onFps?(Double(frameCount) / (now - windowStart))
            frameCount = 0
            windowStart = now
        }
        counter += 1
        guard counter % 5 == 0, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if Self.isFlat(pixelBuffer) { flatCount += 1 } else { flatCount = 0 }
        let flat = flatCount >= 3
        if flat != flatReported {
            flatReported = flat
            onFlat?(flat)
        }
    }

    /// Samples a 9x9 grid of a BGRA buffer; flat when every channel varies by at most a few levels.
    static func isFlat(_ pb: CVPixelBuffer) -> Bool {
        guard CVPixelBufferGetPixelFormatType(pb) == kCVPixelFormatType_32BGRA else { return false }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return false }
        let width = CVPixelBufferGetWidth(pb)
        let height = CVPixelBufferGetHeight(pb)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pb)
        let p = base.assumingMemoryBound(to: UInt8.self)
        var lo = [255, 255, 255]
        var hi = [0, 0, 0]
        for gy in 1...9 {
            let y = height * gy / 10
            for gx in 1...9 {
                let offset = y * bytesPerRow + (width * gx / 10) * 4
                for c in 0..<3 {
                    let v = Int(p[offset + c])
                    lo[c] = min(lo[c], v)
                    hi[c] = max(hi[c], v)
                }
            }
        }
        return (0..<3).allSatisfy { hi[$0] - lo[$0] <= 6 }
    }
}

@preconcurrency import AVFoundation
import SwiftUI
import UIKit

final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

/// Shows the capture session full screen.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let scaleMode: ScaleMode
    /// Changes whenever a new stream starts, so the connection orientation is re-applied.
    let streamToken: String

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.backgroundColor = .black
        view.previewLayer.session = session
        return view
    }

    func updateUIView(_ view: PreviewUIView, context: Context) {
        view.previewLayer.videoGravity = scaleMode == .fit ? .resizeAspect : .resizeAspectFill
        // External sources (HDMI / UVC) are always upright landscape: never rotate them.
        if let connection = view.previewLayer.connection, connection.isVideoRotationAngleSupported(0) {
            connection.videoRotationAngle = 0
        }
    }
}

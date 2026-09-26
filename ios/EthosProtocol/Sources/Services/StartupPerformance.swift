import Foundation
import UIKit

struct StartupPerformance {
    static let shared = StartupPerformance()

    private let appStartTime: CFTimeInterval
    private(set) var firstFrameTime: CFTimeInterval?

    init() {
        appStartTime = CACurrentMediaTime()
    }

    mutating func markFirstFrame() {
        firstFrameTime = CACurrentMediaTime()
        let coldStartMs = Int((firstFrameTime! - appStartTime) * 1000)
        print("[StartupPerformance] First frame rendered in \(coldStartMs)ms")
    }

    func getColdStartTimeMs() -> Int {
        if let frameTime = firstFrameTime {
            return Int((frameTime - appStartTime) * 1000)
        }
        return Int((CACurrentMediaTime() - appStartTime) * 1000)
    }
}

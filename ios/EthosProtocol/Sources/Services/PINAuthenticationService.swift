import Foundation

final class PINAuthenticationService {
    static let shared = PINAuthenticationService()
    private init() {}

    enum PINError: LocalizedError {
        case invalidPIN
        case pinNotSetup
        case pinAlreadySetup

        var errorDescription: String? {
            switch self {
            case .invalidPIN: return "Invalid PIN. Please try again."
            case .pinNotSetup: return "PIN has not been set up."
            case .pinAlreadySetup: return "PIN is already set up."
            }
        }
    }

    func isPINSetup() -> Bool {
        KeychainService.shared.isPINSetup()
    }

    func setupPIN(_ pin: String) throws {
        guard !isPINSetup() else { throw PINError.pinAlreadySetup }
        guard pin.count == 6, pin.allSatisfy({ $0.isNumber }) else {
            throw PINError.invalidPIN
        }
        KeychainService.shared.savePIN(pin)
    }

    func verifyPIN(_ pin: String) throws {
        guard KeychainService.shared.verifyPIN(pin) else {
            throw PINError.invalidPIN
        }
    }

    func resetPIN(_ newPIN: String) throws {
        guard newPIN.count == 6, newPIN.allSatisfy({ $0.isNumber }) else {
            throw PINError.invalidPIN
        }
        KeychainService.shared.deletePIN()
        KeychainService.shared.savePIN(newPIN)
    }
}

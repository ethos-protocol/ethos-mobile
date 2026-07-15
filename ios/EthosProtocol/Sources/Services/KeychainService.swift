import Security
import Foundation

final class KeychainService {
    static let shared = KeychainService()
    private init() {}

    private let tokenKey = "com.ethosprotocol.auth_token"
    private let credentialKey = "com.ethosprotocol.passkey_credential"

    func saveToken(_ token: String) {
        // The auth token must be readable by BackgroundRefreshService's BGAppRefreshTask and by
        // the TTLWidget extension's timeline provider, both of which can run while the device is
        // still locked. `.WhenUnlockedThisDeviceOnly` would make `loadToken()` silently return nil
        // in that case (the request goes out with no Authorization header, and the background
        // TTL check / widget just fail quietly) — so this needs the AfterFirstUnlock variant.
        save(token, forKey: tokenKey, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    }

    func loadToken() -> String? {
        load(forKey: tokenKey)
    }

    func deleteToken() {
        delete(forKey: tokenKey)
    }

    func saveCredentialID(_ id: String) {
        save(id, forKey: credentialKey)
    }

    func loadCredentialID() -> String? {
        load(forKey: credentialKey)
    }

    private func save(_ value: String, forKey key: String, accessible: CFString = kSecAttrAccessibleWhenUnlockedThisDeviceOnly) {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecValueData: data,
            kSecAttrAccessible: accessible
        ]
        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    private func load(forKey key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(forKey key: String) {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrAccount: key]
        SecItemDelete(query as CFDictionary)
    }
}

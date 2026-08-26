import Security
import Foundation

final class KeychainService {
    static let shared = KeychainService()
    private init() {}

    private let tokenKey = "com.ethosprotocol.auth_token"
    private let tokenExpiryKey = "com.ethosprotocol.auth_token_expiry"
    private let credentialKey = "com.ethosprotocol.passkey_credential"
    private let pushTokenKey = "com.ethosprotocol.push_token"

    /// `expiresAt`, when provided, is persisted alongside the token so AuthStore can
    /// schedule a proactive refresh (#3) against `AuthToken.expiresAt` even across an
    /// app relaunch, not just for the lifetime of the in-memory session that fetched it.
    func saveToken(_ token: String, expiresAt: Date? = nil) {
        // The auth token must be readable by BackgroundRefreshService's BGAppRefreshTask and by
        // the TTLWidget extension's timeline provider, both of which can run while the device is
        // still locked. `.WhenUnlockedThisDeviceOnly` would make `loadToken()` silently return nil
        // in that case (the request goes out with no Authorization header, and the background
        // TTL check / widget just fail quietly) — so this needs the AfterFirstUnlock variant.
        save(token, forKey: tokenKey, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        if let expiresAt {
            save(String(expiresAt.timeIntervalSince1970), forKey: tokenExpiryKey,
                 accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
    }

    func loadToken() -> String? {
        load(forKey: tokenKey)
    }

    func loadTokenExpiry() -> Date? {
        guard let raw = load(forKey: tokenExpiryKey), let interval = TimeInterval(raw) else { return nil }
        return Date(timeIntervalSince1970: interval)
    }

    func deleteToken() {
        delete(forKey: tokenKey)
        delete(forKey: tokenExpiryKey)
    }

    func saveCredentialID(_ id: String) {
        save(id, forKey: credentialKey)
    }

    func loadCredentialID() -> String? {
        load(forKey: credentialKey)
    }

    func deleteCredentialID() {
        delete(forKey: credentialKey)
    }

    /// Tracks the device push token last successfully registered with the server,
    /// so AuthStore.signOut() knows what to unregister without needing a fresh
    /// UIApplication device-token callback at sign-out time.
    func savePushToken(_ token: String) {
        save(token, forKey: pushTokenKey)
    }

    func loadPushToken() -> String? {
        load(forKey: pushTokenKey)
    }

    func deletePushToken() {
        delete(forKey: pushTokenKey)
    }

    // MARK: - #226 Trusted-Device Token

    /// Persists the per-vault trusted-device token issued by the server after a
    /// successful 2FA verification with `trust_device: true`.  Stored under a
    /// vault-scoped key so trusting one vault does not accidentally skip 2FA on another.
    func saveTrustToken(_ token: String, vaultID: String, expiresAt: Date) {
        let key = trustTokenKey(for: vaultID)
        save(token, forKey: key, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        let expiryKey = key + ".expiry"
        save(String(expiresAt.timeIntervalSince1970), forKey: expiryKey,
             accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    }

    func loadTrustToken(vaultID: String) -> String? {
        load(forKey: trustTokenKey(for: vaultID))
    }

    /// Returns the expiry of the stored trust token for `vaultID`, or nil if absent/expired.
    func trustTokenExpiry(vaultID: String) -> Date? {
        let key = trustTokenKey(for: vaultID) + ".expiry"
        guard let raw = load(forKey: key), let interval = TimeInterval(raw) else { return nil }
        return Date(timeIntervalSince1970: interval)
    }

    /// Deletes the trusted-device token for `vaultID` (e.g. on remote revocation or sign-out).
    func deleteTrustToken(vaultID: String) {
        let key = trustTokenKey(for: vaultID)
        delete(forKey: key)
        delete(forKey: key + ".expiry")
    }

    private func trustTokenKey(for vaultID: String) -> String {
        "com.ethosprotocol.device_trust_token.\(vaultID)"
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

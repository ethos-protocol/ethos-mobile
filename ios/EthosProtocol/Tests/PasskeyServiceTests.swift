import XCTest
import AuthenticationServices
@testable import EthosProtocol

// MARK: - #8 Excluded Credentials Tests

final class PasskeyServiceExcludedCredentialsTests: XCTestCase {

    func test_excludedCredentialDescriptors_mapsEachIDToADescriptor() {
        let idA = Data([0x01, 0x02, 0x03]).base64URLEncodedString()
        let idB = Data([0xAA, 0xBB]).base64URLEncodedString()

        let descriptors = PasskeyService.excludedCredentialDescriptors(from: [idA, idB])

        XCTAssertEqual(descriptors.count, 2)
        XCTAssertEqual(descriptors[0].credentialID, Data([0x01, 0x02, 0x03]))
        XCTAssertEqual(descriptors[1].credentialID, Data([0xAA, 0xBB]))
    }

    func test_excludedCredentialDescriptors_emptyInput_returnsEmpty() {
        XCTAssertTrue(PasskeyService.excludedCredentialDescriptors(from: []).isEmpty)
    }

    func test_excludedCredentialDescriptors_skipsUndecodableIDs() {
        let valid = Data([0x01]).base64URLEncodedString()
        let descriptors = PasskeyService.excludedCredentialDescriptors(from: [valid, "not valid base64url!!"])

        XCTAssertEqual(descriptors.count, 1)
        XCTAssertEqual(descriptors[0].credentialID, Data([0x01]))
    }
}

// MARK: - #7 ASAuthorizationError → PasskeyError Mapping Tests

final class PasskeyErrorMappingTests: XCTestCase {

    func test_canceled_mapsToUserCancelled() {
        let mapped = PasskeyError.map(ASAuthorizationError(.canceled), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .userCancelled)
    }

    func test_notInteractive_mapsToNotInteractive() {
        let mapped = PasskeyError.map(ASAuthorizationError(.notInteractive), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .notInteractive)
    }

    func test_matchedExcludedCredential_mapsToCredentialAlreadyExists() {
        let mapped = PasskeyError.map(ASAuthorizationError(.matchedExcludedCredential), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .credentialAlreadyExists)
    }

    func test_unknownAuthorizationErrorCode_fallsBackToProvidedDefault_registration() {
        let mapped = PasskeyError.map(ASAuthorizationError(.unknown), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .registrationFailed)
    }

    func test_failedAuthorizationErrorCode_fallsBackToProvidedDefault_authentication() {
        let mapped = PasskeyError.map(ASAuthorizationError(.failed), fallback: .authenticationFailed)
        XCTAssertEqual(mapped, .authenticationFailed)
    }

    func test_nonAuthorizationError_fallsBackToProvidedDefault() {
        struct SomeOtherError: Error {}
        let mapped = PasskeyError.map(SomeOtherError(), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .registrationFailed)
    }

    func test_allCases_haveDistinctNonEmptyDescriptions() {
        let cases: [PasskeyError] = [.registrationFailed, .authenticationFailed, .userCancelled, .notInteractive, .credentialAlreadyExists]
        let descriptions = cases.compactMap { $0.errorDescription }

        XCTAssertEqual(descriptions.count, cases.count, "Every case should have a description")
        XCTAssertEqual(Set(descriptions).count, cases.count, "Each case should have distinct copy")
    }
}

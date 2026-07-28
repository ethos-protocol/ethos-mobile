package com.ethosprotocol

import java.io.ByteArrayOutputStream

// Minimal CBOR (RFC 8949) encoder used only to build test fixtures for
// PasskeyServiceTest/ApiClientTest-adjacent CBOR-parsing logic. Deliberately independent
// of the decoder under test (PasskeyService's CborReader) so a passing test actually
// validates the decoder against a spec-compliant encoding, not just against itself.

private fun cborHeader(majorType: Int, length: Int): ByteArray {
    val mt = majorType shl 5
    return when {
        length < 24 -> byteArrayOf((mt or length).toByte())
        length < 256 -> byteArrayOf((mt or 24).toByte(), length.toByte())
        else -> byteArrayOf((mt or 25).toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
    }
}

private fun cborInt(value: Long): ByteArray =
    if (value >= 0) cborHeader(0, value.toInt()) else cborHeader(1, (-value - 1).toInt())

private fun cborBytes(value: ByteArray): ByteArray = cborHeader(2, value.size) + value

private fun cborText(value: String): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return cborHeader(3, bytes.size) + bytes
}

private fun cborValue(value: Any): ByteArray = when (value) {
    is Long -> cborInt(value)
    is Int -> cborInt(value.toLong())
    is ByteArray -> cborBytes(value)
    is String -> cborText(value)
    else -> error("Unsupported CBOR test fixture value: $value")
}

fun cborMap(entries: Map<Any, Any>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(cborHeader(5, entries.size))
    entries.forEach { (k, v) ->
        out.write(cborValue(k))
        out.write(cborValue(v))
    }
    return out.toByteArray()
}

// Builds a synthetic authData blob (WebAuthn §6.1) containing attested credential data,
// with the given COSE_Key bytes as the credentialPublicKey.
fun cborAuthData(credentialId: ByteArray, coseKeyBytes: ByteArray): ByteArray =
    ByteArray(32) { it.toByte() } + // rpIdHash (arbitrary, unchecked by the code under test)
        byteArrayOf(0x41) + // flags: AT (attested credential data present) bit set
        byteArrayOf(0, 0, 0, 0) + // signCount
        ByteArray(16) + // aaguid (arbitrary)
        byteArrayOf((credentialId.size shr 8).toByte(), (credentialId.size and 0xFF).toByte()) +
        credentialId +
        coseKeyBytes

fun cborEs256CoseKey(x: ByteArray, y: ByteArray): ByteArray = cborMap(
    linkedMapOf<Any, Any>(
        1L to 2L, // kty: EC2
        3L to -7L, // alg: ES256
        -1L to 1L, // crv: P-256
        -2L to x,
        -3L to y
    )
)

fun cborAttestationObject(authData: ByteArray): ByteArray = cborMap(
    linkedMapOf<Any, Any>(
        "fmt" to "none",
        "attStmt" to cborMap(emptyMap()),
        "authData" to authData
    )
)

package com.ethosprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #779 — The manifest must reference a network security config, and that config must
 * forbid cleartext traffic for the app and for `api.ethos-protocol.app`.
 *
 * Parses the XML resources directly, so it runs as a plain JVM unit test.
 */
class NetworkSecurityConfigTest {

    private val manifest = parse("src/main/AndroidManifest.xml")
    private val config = parse("src/main/res/xml/network_security_config.xml")

    @Test
    fun manifestReferencesNetworkSecurityConfig() {
        val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals(
            "@xml/network_security_config",
            application.getAttribute("android:networkSecurityConfig"),
        )
    }

    @Test
    fun baseConfigForbidsCleartext() {
        val base = config.getElementsByTagName("base-config").item(0) as org.w3c.dom.Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun apiDomainForbidsCleartext() {
        val domains = config.getElementsByTagName("domain-config")
        val apiDomain = (0 until domains.length)
            .map { domains.item(it) as org.w3c.dom.Element }
            .firstOrNull { el ->
                val domain = el.getElementsByTagName("domain").item(0)
                domain?.textContent == "api.ethos-protocol.app"
            }
        assertTrue("api.ethos-protocol.app must have a domain-config", apiDomain != null)
        assertEquals("false", apiDomain!!.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun configDoesNotCommitPinHashes() {
        // Pins come from ETHOS_CERT_PINS at build time, never from this file.
        assertFalse(config.getElementsByTagName("pin-set").length > 0)
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(File(path))
}

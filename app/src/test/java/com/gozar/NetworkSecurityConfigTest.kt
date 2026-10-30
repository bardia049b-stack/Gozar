package com.gozar

import android.app.Application
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NetworkSecurityConfigTest {
    @Test
    fun customRootsAreLimitedToFastlyDomainsAlongsideSystemRoots() {
        val context = RuntimeEnvironment.getApplication()
        val parser = context.resources.getXml(R.xml.network_security_config)
        val domains = mutableListOf<String>()
        var includesSubdomains = false
        var system = false
        var bundled = false
        var domainConfigs = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "domain-config" -> domainConfigs++
                    "domain" -> {
                        includesSubdomains = parser.getAttributeBooleanValue(null, "includeSubdomains", false)
                        domains.add(parser.nextText())
                    }
                    "certificates" -> {
                        if (parser.getAttributeValue(null, "src") == "system") system = true
                        if (parser.getAttributeResourceValue(null, "src", 0) == R.raw.fastly_roots) bundled = true
                    }
                }
            }
            event = parser.next()
        }
        parser.close()
        assertEquals(1, domainConfigs)
        assertEquals(listOf("fastly-masque.net"), domains)
        assertTrue(includesSubdomains)
        assertTrue(system)
        assertTrue(bundled)
    }

    @Test
    fun bundledRootsMatchOfficialGlobalSignAndAndroid14Roots() {
        val context = RuntimeEnvironment.getApplication()
        val certificates = context.resources.openRawResource(R.raw.fastly_roots).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        val fingerprints = certificates.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
        }.toSet()
        assertEquals(setOf(
            "CBB9C44D84B8043E1050EA31A69F514955D7BFD2E2C6B49301019AD61D9F5058",
            "4FA3126D8D3A11D1C4855A4F807CBAD6CF919D3A5A88B03BEA2C6372D93C40C9"
        ), fingerprints)
    }
}

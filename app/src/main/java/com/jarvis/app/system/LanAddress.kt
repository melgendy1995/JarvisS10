package com.jarvis.app.system

import java.net.Inet4Address
import java.net.NetworkInterface

/** One routable IPv4 seen on an interface, whether it is the Wi-Fi radio. */
data class LanCandidate(val ipv4: String, val wifi: Boolean)

/**
 * Picks the LAN IPv4 shown on the shelf. Site-local only (RFC 1918, which also
 * excludes loopback, link-local, multicast, and Tailscale's 100.64.0.0/10),
 * preferring wlan when several qualifying networks are up.
 */
object LanAddress {
    private val SITE_LOCAL = Regex(
        """^(?:10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})$""",
    )

    fun pick(candidates: List<LanCandidate>): String? {
        return candidates
            .filter { it.ipv4.isNotEmpty() && SITE_LOCAL.matches(it.ipv4) }
            .maxByOrNull { it.wifi }?.ipv4
    }

    /** "192.168.8.211:8088" for the shelf, or "No Wi-Fi" when nothing qualifies. */
    fun display(ip: String?, port: Int): String {
        return if (ip.isNullOrBlank()) "No Wi-Fi" else "$ip:$port"
    }

    /** Android-side scan feeding [pick]; not unit-testable, kept as thin as possible. */
    fun currentCandidates(): List<LanCandidate> {
        val out = ArrayList<LanCandidate>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
        for (nif in interfaces.asSequence()) {
            if (!nif.isUp || nif.isLoopback) continue
            val wifi = nif.name.startsWith("wlan") || nif.displayName.orEmpty().startsWith("wlan")
            for (address in nif.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    out.add(LanCandidate(address.hostAddress.orEmpty(), wifi))
                }
            }
        }
        return out
    }
}

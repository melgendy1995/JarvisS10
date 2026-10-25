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

    private val TAILSCALE = Regex(
        """^100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}$""",
    )

    /** First IPv4 in 100.64.0.0/10 (100.64.0.0–100.127.255.255), typically tun0. */
    fun pickTailscale(candidates: List<LanCandidate>): String? {
        return candidates
            .filter { it.ipv4.isNotEmpty() && TAILSCALE.matches(it.ipv4) }
            .firstOrNull()?.ipv4
    }

    /**
     * Shelf text: "192.168.8.211:8088 · TS 100.91.216.7:8088" when a Tailscale
     * address exists, or "192.168.8.211:8088" / "No Wi-Fi" unchanged otherwise.
     */
    fun display(ip: String?, port: Int, tailscale: String? = null): String {
        val lan = if (ip.isNullOrBlank()) "No Wi-Fi" else "$ip:$port"
        return tailscale
            ?.takeIf { it.isNotBlank() }
            ?.let { "$lan · TS $it:$port" }
            ?: lan
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

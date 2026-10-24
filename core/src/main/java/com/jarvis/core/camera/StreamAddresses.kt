package com.jarvis.core.camera

enum class HostKind {
    LAN,
    TAILSCALE,
    IGNORE,
}

data class CameraEndpoints(
    val lan: List<String>,
    val tailscale: List<String>,
) {
    fun lanText(): String = lan.joinToString("\n").ifBlank { "No LAN address" }

    fun tailscaleText(): String = tailscale.joinToString("\n").ifBlank { "Tailscale is not connected" }

    companion object {
        fun from(addresses: List<ByteArray>, port: Int): CameraEndpoints {
            val lan = ArrayList<String>()
            val tailscale = ArrayList<String>()
            for (address in addresses) {
                when (classify(address)) {
                    HostKind.LAN -> hostUrl(address, port)?.let { lan += it }
                    HostKind.TAILSCALE -> hostUrl(address, port)?.let { tailscale += it }
                    HostKind.IGNORE -> Unit
                }
            }
            return CameraEndpoints(lan.distinct().sorted(), tailscale.distinct().sorted())
        }
    }
}

/**
 * Tailscale's CGNAT range is 100.64.0.0/10 (100.64.0.0 through 100.127.255.255).
 * Other IPv4 unicast addresses are treated as LAN. Loopback, link-local, and
 * multicast addresses are hidden.
 */
fun classify(address: ByteArray): HostKind {
    if (address.size != 4) return HostKind.IGNORE
    val a = address[0].toInt() and 0xff
    val b = address[1].toInt() and 0xff
    if (a == 0 || a == 127 || a >= 224) return HostKind.IGNORE
    if (a == 169 && b == 254) return HostKind.IGNORE
    if (a == 100 && b in 64..127) return HostKind.TAILSCALE
    return HostKind.LAN
}

fun hostUrl(address: ByteArray, port: Int): String? {
    if (address.size != 4 || port !in 1..65535) return null
    val a = address[0].toInt() and 0xff
    val b = address[1].toInt() and 0xff
    val c = address[2].toInt() and 0xff
    val d = address[3].toInt() and 0xff
    return "http://$a.$b.$c.$d:$port/"
}

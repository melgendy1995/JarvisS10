package com.jarvis.app.system

import com.jarvis.core.camera.CameraEndpoints
import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN addresses, plus Tailscale when an interface has a 100.64.0.0/10 address. */
object StreamAddressLookup {
    fun lookup(port: Int): CameraEndpoints {
        val found = ArrayList<ByteArray>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return CameraEndpoints.from(emptyList(), port)
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addresses = nif.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address) found.add(address.address)
            }
        }
        return CameraEndpoints.from(found, port)
    }
}

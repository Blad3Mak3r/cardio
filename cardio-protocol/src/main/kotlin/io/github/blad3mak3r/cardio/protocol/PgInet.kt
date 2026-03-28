package io.github.blad3mak3r.cardio.protocol

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Represents a PostgreSQL INET value: an IP address (v4 or v6) with an optional network mask.
 *
 * This is a lightweight wrapper around [InetAddress] that adds netmask support.
 *
 * @property address The IP address
 * @property netmask The network mask (0-32 for IPv4, 0-128 for IPv6). Defaults to full mask.
 */
data class PgInet(
    val address: InetAddress,
    val netmask: Int = if (address is Inet4Address) 32 else 128
) {
    init {
        val maxMask = if (address is Inet4Address) 32 else 128
        require(netmask in 0..maxMask) {
            "Netmask must be between 0 and $maxMask for ${if (address is Inet4Address) "IPv4" else "IPv6"}"
        }
    }

    /**
     * Returns true if this is an IPv4 address.
     */
    val isIPv4: Boolean get() = address is Inet4Address

    /**
     * Returns true if this is an IPv6 address.
     */
    val isIPv6: Boolean get() = address is Inet6Address

    /**
     * Returns the string representation in PostgreSQL format: "address/netmask"
     */
    override fun toString(): String = "${address.hostAddress}/$netmask"

    companion object {
        /**
         * Parses a string in "address/netmask" format.
         * If netmask is omitted, defaults to 32 for IPv4 or 128 for IPv6.
         */
        fun parse(value: String): PgInet {
            val parts = value.split('/')
            val address = InetAddress.getByName(parts[0])
            val netmask = if (parts.size > 1) {
                parts[1].toInt()
            } else {
                if (address is Inet4Address) 32 else 128
            }
            return PgInet(address, netmask)
        }

        /**
         * Creates a PgInet from an InetAddress with default netmask (32 for IPv4, 128 for IPv6).
         */
        fun of(address: InetAddress): PgInet = PgInet(address)

        /**
         * Creates a PgInet from an InetAddress with specified netmask.
         */
        fun of(address: InetAddress, netmask: Int): PgInet = PgInet(address, netmask)
    }
}

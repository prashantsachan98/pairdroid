package org.pairdroid.pairdroid

import java.io.InputStream
import java.net.InetAddress

object Rules {
    const val MAX_REQUEST = 65536
    fun readLine(input: InputStream, max: Int = MAX_REQUEST): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            require(b != -1) { "Truncated message" }
            if (b == 10) break
            require(out.size() < max) { "Message too large" }
            out.write(b)
        }
        return out.toString("UTF-8")
    }
    fun local(address: InetAddress): Boolean = address.isSiteLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress
    fun downtime(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }
    fun blocked(usedMs: Long, limitMinutes: Int, minute: Int, start: Int, end: Int, allowed: Boolean): Boolean =
        !allowed && ((limitMinutes > 0 && usedMs >= limitMinutes * 60000L) || downtime(minute, start, end))
}

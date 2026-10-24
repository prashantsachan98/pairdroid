package org.pairdroid.pairdroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class RulesTest {
    @Test fun boundedProtocolRejectsTruncationAndOversize() {
        assertEquals("{}", Rules.readLine(ByteArrayInputStream("{}\nignored".toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { Rules.readLine(ByteArrayInputStream("{}".toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) { Rules.readLine(ByteArrayInputStream("abcd\n".toByteArray()), 3) }
        assertEquals("abc", Rules.readLine(ByteArrayInputStream("abc\n".toByteArray()), 3))
    }
    @Test fun downtimeAcrossMidnightAndBoundary() {
        assertTrue(Rules.downtime(23*60,22*60,7*60))
        assertTrue(Rules.downtime(6*60,22*60,7*60))
        assertFalse(Rules.downtime(7*60,22*60,7*60))
        assertFalse(Rules.downtime(12*60,22*60,7*60))
        assertFalse(Rules.downtime(0,0,0))
        assertTrue(Rules.downtime(8*60,8*60,9*60))
    }
    @Test fun limitsAndAlwaysAllowed() {
        assertTrue(Rules.blocked(60000,1,0,0,0,false))
        assertFalse(Rules.blocked(59999,1,0,0,0,false))
        assertFalse(Rules.blocked(Long.MAX_VALUE,0,0,0,0,false))
        assertFalse(Rules.blocked(900000,1,23*60,22*60,7*60,true))
    }
    @Test fun publicAndLoopbackAddressesRejected() {
        assertTrue(Rules.local(InetAddress.getByName("192.168.1.5")))
        assertTrue(Rules.local(InetAddress.getByName("172.20.0.5")))
        assertFalse(Rules.local(InetAddress.getByName("8.8.8.8")))
        assertFalse(Rules.local(InetAddress.getByName("127.0.0.1")))
        assertFalse(Rules.local(InetAddress.getByName("224.0.0.1")))
    }
}

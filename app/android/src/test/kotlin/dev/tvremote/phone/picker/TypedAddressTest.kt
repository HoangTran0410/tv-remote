package dev.tvremote.phone.picker

import org.junit.Assert.assertEquals
import org.junit.Test

class TypedAddressTest {
    private fun ok(h: String, p: Int = 0) = TypedAddress.Ok(h, p)

    @Test fun ipv4() {
        assertEquals(ok("192.168.1.5"), TypedAddress.parse(" 192.168.1.5 "))
        assertEquals(ok("192.168.1.5", 5877), TypedAddress.parse("192.168.1.5:5877"))
        assertEquals(ok("192.168.1.5", 5877), TypedAddress.parse("192.168.1.5 5877"))
        assertEquals(ok("100.64.0.9"), TypedAddress.parse("100.64.0.9"))
    }

    @Test fun separatorsBecomeDots() {
        assertEquals(ok("192.168.1.5"), TypedAddress.parse("192 168 1 5"))
        assertEquals(ok("192.168.1.5"), TypedAddress.parse("192,168,1,5"))
        assertEquals(ok("192.168.1.5", 5555), TypedAddress.parse("192 168 1 5 5555"))
    }

    @Test fun names() {
        assertEquals(ok("tv.local"), TypedAddress.parse("tv.local"))
        assertEquals(ok("my-tv.tail1234.ts.net", 5555), TypedAddress.parse("my-tv.tail1234.ts.net:5555"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("bravia"))
    }

    @Test fun ipv6() {
        assertEquals(ok("fe80::1"), TypedAddress.parse("[fe80::1]"))
        assertEquals(ok("fd7a::2", 5555), TypedAddress.parse("[fd7a::2]:5555"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("fe80::1 zz"))
    }

    @Test fun downloaderCodeAndJunk() {
        assertEquals(TypedAddress.DownloaderCode, TypedAddress.parse("2397346"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("42"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("192 168 100"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("192.168.1.256"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("0.0.0.0"))
        assertEquals(TypedAddress.Bad, TypedAddress.parse("192.168.1.5:99999"))
        assertEquals(TypedAddress.Empty, TypedAddress.parse("   "))
    }
}

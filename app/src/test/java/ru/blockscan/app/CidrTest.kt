package ru.blockscan.app

import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.core.*
import java.math.BigInteger

class CidrTest {
    private fun ok(r: PlanResult): PlanResult.Ok = r as PlanResult.Ok

    @Test fun ipv4Validation() {
        assertNotNull(IpParser.parse("192.168.1.1"))
        assertNotNull(IpParser.parse(" 1.2.3.4 "))
        for (bad in listOf("256.1.1.1", "1.2.3", "01.2.3.4", "1.2.3.4.5", "a.b.c.d", "", "1.2.3.-4", "1..3.4", "1.2.3.4/24"))
            assertNull("должно быть невалидным: $bad", IpParser.parse(bad))
    }

    @Test fun ipv6ParsingAndFormatting() {
        assertEquals("::1", IpParser.parse("::1").toString())
        assertEquals("::", IpParser.parse("::").toString())
        assertEquals("1::", IpParser.parse("1::").toString())
        assertEquals("2001:db8::1", IpParser.parse("2001:0db8:0000:0000:0000:0000:0000:0001").toString())
        assertEquals("2001:db8::1:0:0:1", IpParser.parse("2001:db8:0:0:1:0:0:1").toString())
        assertEquals("::ffff:102:304", IpParser.parse("::ffff:1.2.3.4").toString())
        assertEquals("2606:4700::1111", IpParser.parse("2606:4700:0:0:0:0:0:1111").toString())
        for (bad in listOf("1::2::3", ":::", "12345::", "1:2:3:4:5:6:7", "fe80::1%eth0", "g::1", "1:2:3:4:5:6:7:8:9", ":1::2"))
            assertNull("должно быть невалидным: $bad", IpParser.parse(bad))
    }

    @Test fun subnetCalculatorFor24() {
        val c = Cidr.parse("192.168.1.0/24")!!
        assertFalse(c.hadHostBits)
        val i = SubnetCalc.info(c.cidr)
        assertEquals("192.168.1.0", i.network)
        assertEquals("255.255.255.0", i.mask)
        assertEquals("192.168.1.255", i.broadcast)
        assertEquals("192.168.1.1", i.firstHost)
        assertEquals("192.168.1.254", i.lastHost)
        assertEquals(BigInteger.valueOf(256), i.total)
    }

    @Test fun hostBitsAreNormalized() {
        val p = Cidr.parse("192.168.1.77/24")!!
        assertTrue(p.hadHostBits)
        assertEquals("192.168.1.0/24", p.cidr.toString())
        assertNull(Cidr.parse("1.2.3.4/33"))
        assertNull(Cidr.parse("1.2.3.4/"))
        assertNull(Cidr.parse("1.2.3.4/a"))
        assertNull(Cidr.parse("/24"))
        assertNull(Cidr.parse("2001:db8::/129"))
    }

    @Test fun allAddressesOf24() {
        val set = ok(ScanPlanner.fromCidr("192.168.1.0/24", excludeEdges = false)).set
        assertEquals(256L, set.count)
        val all = set.sequence().toList()
        assertEquals(256, all.size)
        assertEquals("192.168.1.0", all.first())
        assertEquals("192.168.1.255", all.last())
        assertEquals(256, all.toSet().size)
    }

    @Test fun excludeNetworkAndBroadcast() {
        val set = ok(ScanPlanner.fromCidr("192.168.1.0/24", excludeEdges = true)).set
        assertEquals(254L, set.count)
        assertEquals("192.168.1.1", set.sequence().first())
        assertEquals("192.168.1.254", set.sequence().last())
    }

    @Test fun smallPrefixesIgnoreEdgeExclusion() {
        val r31 = ok(ScanPlanner.fromCidr("10.0.0.0/31", true))
        assertEquals(2L, r31.set.count)
        assertTrue(r31.warnings.isNotEmpty())
        assertEquals(1L, ok(ScanPlanner.fromCidr("10.0.0.5/32", true)).set.count)
        assertEquals(1L, ok(ScanPlanner.fromCidr("10.0.0.5", false)).set.count)
    }

    @Test fun arbitraryPrefixLengths() {
        assertEquals(1024L, ok(ScanPlanner.fromCidr("10.0.0.0/22", false)).set.count)
        assertEquals(65_536L, ok(ScanPlanner.fromCidr("10.0.0.0/16", false)).set.count)
        assertEquals(8L, ok(ScanPlanner.fromCidr("10.0.0.8/29", false)).set.count)
    }

    @Test fun hugeRangesAreRejectedWithoutOverflow() {
        assertTrue(ScanPlanner.fromCidr("10.0.0.0/15", false) is PlanResult.Error)
        assertTrue(ScanPlanner.fromCidr("0.0.0.0/0", false) is PlanResult.Error)
        assertTrue(ScanPlanner.fromCidr("2001:db8::/64", false) is PlanResult.Error)
        assertTrue(ScanPlanner.fromCidr("::/0", false) is PlanResult.Error)
    }

    @Test fun topOfAddressSpaceDoesNotWrap() {
        val s = ok(ScanPlanner.fromCidr("255.255.255.254/31", false)).set.sequence().toList()
        assertEquals(listOf("255.255.255.254", "255.255.255.255"), s)
    }

    @Test fun ipv6Subnets() {
        assertEquals(256L, ok(ScanPlanner.fromCidr("2001:db8::/120", false)).set.count)
        val s = ok(ScanPlanner.fromCidr("2001:db8::/126", false)).set.sequence().toList()
        assertEquals(listOf("2001:db8::", "2001:db8::1", "2001:db8::2", "2001:db8::3"), s)
    }

    @Test fun customRange() {
        val set = ok(ScanPlanner.fromRange("10.0.0.250", "10.0.1.5")).set
        assertEquals(12L, set.count)
        val l = set.sequence().toList()
        assertEquals("10.0.0.250", l.first()); assertEquals("10.0.1.5", l.last())
        assertTrue(ScanPlanner.fromRange("10.0.0.9", "10.0.0.1") is PlanResult.Error)
        assertTrue(ScanPlanner.fromRange("10.0.0.1", "::1") is PlanResult.Error)
        assertTrue(ScanPlanner.fromRange("x", "10.0.0.1") is PlanResult.Error)
    }

    @Test fun pastedList() {
        val r = ok(ScanPlanner.fromList("1.1.1.1, 8.8.8.8\n1.1.1.1 bad ::1;999.1.1.1"))
        assertEquals(3L, r.set.count)
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "::1"), r.set.sequence().toList())
        assertTrue(r.warnings.single().contains("2"))
        assertTrue(ScanPlanner.fromList("   ") is PlanResult.Error)
        assertTrue(ScanPlanner.fromList("foo bar") is PlanResult.Error)
    }

    @Test fun nonPublicClassifier() {
        for (a in listOf("192.168.1.1", "10.1.2.3", "172.16.5.5", "127.0.0.1", "100.64.0.1", "2001:db8::1", "fe80::1"))
            assertTrue(a, IpClassifier.isNonPublic(IpParser.parse(a)!!))
        for (a in listOf("1.1.1.1", "8.8.8.8", "172.32.0.1", "2606:4700::1111"))
            assertFalse(a, IpClassifier.isNonPublic(IpParser.parse(a)!!))
    }
}

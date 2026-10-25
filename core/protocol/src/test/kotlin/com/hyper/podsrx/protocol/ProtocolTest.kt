package com.hyper.podsrx.protocol

import kotlin.test.*

class ProtocolTest {
    @Test fun batteryComponentsAreIdentifiedWithoutAssumingOrder() {
        val p = Aacp.header + byteArrayOf(4,0,3, 2,0,81,2,0, 4,0,0,1,0, 8,0,-1,4,0)
        val event = Aacp.decode(p) as Event.Batteries
        assertEquals(81, event.values.first { it.component == 2 }.level)
        assertEquals(0, event.values.first { it.component == 4 }.level)
        assertNull(event.values.first { it.component == 8 }.level)
    }
    @Test fun shortAndMalformedBatteryFramesAreRejected() {
        assertNull(Aacp.decode(byteArrayOf(4,0,4)))
        assertNull(Aacp.decode(Aacp.header + byteArrayOf(4,0,3,2,0,50)))
        assertNull(Aacp.decode(Aacp.header + byteArrayOf(4,0,-1)))
    }
    @Test fun controlWireFormatIsExact() {
        assertContentEquals(byteArrayOf(4,0,4,0,9,0,13,2,0,0,0), Aacp.control(13, listOf(2)))
        assertFailsWith<IllegalArgumentException> { Aacp.control(13, listOf(300)) }
    }
    @Test fun fragmentedAndConcatenatedControlPacketsArePreserved() {
        val a = Aacp.control(13,listOf(2)); val b = Aacp.control(10,listOf(1))
        val f = AacpFramer()
        assertTrue(f.feed(a.copyOf(5)).isEmpty())
        val frames = f.feed(a.drop(5).toByteArray() + b)
        assertEquals(2, frames.size); assertContentEquals(a, frames[0]); assertContentEquals(b, frames[1])
    }
    @Test fun keysAreBoundedAndNeverPrinted() {
        val p = Aacp.header + byteArrayOf(0x31,0,1,1,0,16,0) + ByteArray(16) { it.toByte() }
        val e = Aacp.decode(p) as Event.Keys
        assertEquals(16,e.values[1]!!.size); assertEquals("Keys(redacted)",e.toString())
        assertNull(Aacp.decode(p.copyOf(15)))
    }
    @Test fun unknownBatteryDoesNotBecomeZero() {
        val p = ByteArray(27).also { it[0]=7;it[1]=25;it[6]=-1;it[7]=15 }
        assertTrue(ProximityCodec.decode(p)!!.batteries.all { it.level == null })
    }
    @Test fun malformedAddressesAndKeysNeverCrashScanning() {
        assertFalse(ProximityCodec.matchesIdentity("invalid",ByteArray(16)))
        assertFalse(ProximityCodec.matchesIdentity("AA:BB:CC:DD:EE:FF",ByteArray(2)))
        assertNull(ProximityCodec.decode(ByteArray(4)))
    }
    @Test fun addressRotationAndSilenceDoNotDuplicatePopup() {
        val session = LidSession()
        assertTrue(session.observe(true,10000,10000))
        assertFalse(session.confirm(10899))
        assertTrue(session.confirm(10900))
        session.observe(true,11000,11000)
        assertFalse(session.confirm(11900))
        session.expire()
        session.observe(true,20000,20000)
        assertFalse(session.confirm(20900))
        session.observe(false,21000,21000)
        session.observe(true,22000,22000)
        assertTrue(session.confirm(22900))
    }
    @Test fun outOfOrderOpenFrameCannotOverrideLatestClosedFrame() {
        val session=LidSession()
        session.observe(true,10000,16000)
        session.observe(false,15000,16000)
        assertFalse(session.observe(true,12000,16000))
        assertEquals(false,session.open)
        assertNull(session.confirmationDelay(16000))
        assertFalse(session.confirm(17000))
    }
    @Test fun briefOpenDuringClosingBatchDoesNotShowCard() {
        val session=LidSession()
        session.observe(false,10000,15000)
        session.observe(true,11000,15000)
        session.observe(false,14000,15000)
        assertFalse(session.confirm(16000))
        session.observe(true,30000,35000)
        assertFalse(session.confirm(35899))
        assertTrue(session.confirm(35900))
        assertFalse(session.confirm(36000))
    }
    @Test fun repeatedSamplesDoNotPostponeConfirmationAndStaleFramesAreIgnored() {
        val session=LidSession()
        assertFalse(session.observe(true,10000,26000))
        assertFalse(session.observe(true,30000,29000))
        assertNull(session.open)
        session.observe(true,30000,31000)
        session.observe(true,30500,31500)
        assertEquals(400L,session.confirmationDelay(31500))
        assertTrue(session.confirm(31900))
        session.observe(false,32000,32000)
        session.observe(true,32500,32500)
        assertFalse(session.confirm(34000))
    }
    @Test fun equalizerWireRoundtripAndBounds() {
        val packet=Aacp.equalizer(listOf(2,60,50,40))
        assertEquals(Event.Equalizer(2,60,50,40),Aacp.decode(packet))
        assertNull(Aacp.decode(packet.copyOf(11)))
        assertFailsWith<IllegalArgumentException>{Aacp.equalizer(listOf(2,101,0,0))}
    }
    @Test fun equalizerAndControlPacketsCanBeConcatenated() {
        val f=AacpFramer()
        val frames=f.feed(Aacp.equalizer(listOf(1,50,50,50))+Aacp.control(13,listOf(2)))
        assertEquals(2,frames.size)
        assertTrue(Aacp.decode(frames[0]) is Event.Equalizer)
        assertTrue(Aacp.decode(frames[1]) is Event.Control)
    }
    @Test fun proximityMessageCanFollowAnotherContinuityMessage() {
        val proximity=ByteArray(27).also{it[0]=7;it[1]=25}
        val frames=AppleAdvertisement.proximityMessages(byteArrayOf(16,2,1,0)+proximity+byteArrayOf(18,1,0))
        assertEquals(1,frames.size)
        assertContentEquals(proximity,frames.single())
        assertTrue(AppleAdvertisement.proximityMessages(byteArrayOf(16,10,0,7,25)).isEmpty())
        assertNull(ProximityCodec.decode(proximity+byteArrayOf(18,1,0)))
    }
    @Test fun renameUsesUtf8LengthAndGuestKeysAreNotOwnerKeys() {
        val name="耳机"
        assertContentEquals(Aacp.header+byteArrayOf(0x1a,0,1,6,0)+name.toByteArray(),Aacp.rename(name))
        assertFailsWith<IllegalArgumentException>{Aacp.rename("bad\u0000name")}
        val guest=Aacp.header+byteArrayOf(0x31,0,1,1,1,16,0)+ByteArray(16)
        assertTrue((Aacp.decode(guest) as Event.Keys).values.isEmpty())
    }
}

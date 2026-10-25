package com.hyper.podsrx.protocol

import kotlin.test.*
import kotlin.math.*

class MotionTest {
    private fun frame(command:Boolean=false,service:Int=16,blob:ByteArray=blob()):ByteArray {
        val nested=(if(command)byteArrayOf(8,service.toByte())else byteArrayOf())+byteArrayOf(0x1a,blob.size.toByte())+blob
        val body=byteArrayOf(if(command)0x3a else 0x1a,nested.size.toByte())+nested
        return Aacp.header+byteArrayOf(0x17,0,0,0,16,0,body.size.toByte(),0)+body
    }
    private fun blob()=ByteArray(58).also{p->
        p[0]=1;p[9]=3
        listOf(10000,-14000,9000).forEachIndexed{i,v->p[20+2*i]=v.toByte();p[21+2*i]=(v shr 8).toByte()}
    }
    @Test fun bothRtBuddyEnvelopeShapesDecodeSameNormalizedQuaternion(){
        val stream=Aacp.decode(frame()) as Event.Motion
        val command=Aacp.decode(frame(true)) as Event.Motion
        assertEquals(-1,stream.service);assertEquals(16,command.service)
        assertEquals(stream.orientation,command.orientation)
        val q=stream.orientation
        assertEquals(1.0,q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w,1e-8)
        assertTrue(q.y<0)
    }
    @Test fun malformedProtobufLengthsAndUnrecognizedSensorLayoutsAreRejected(){
        val good=frame()
        for(n in 0 until good.size)assertNull(MotionCodec.decode(good.copyOf(n)))
        assertNull(MotionCodec.decode(frame(true,99)))
        assertNull(MotionCodec.decode(frame(blob=blob().also{it[9]=0})))
        assertNull(MotionCodec.decode(frame(blob=ByteArray(58).also{it[0]=1;it[9]=3})))
        assertNull(MotionCodec.decode(good.copyOf().also{it[13]=0x7f}))
        assertNull(MotionCodec.decode(good.copyOf().also{it[10]=0xff.toByte();it[11]=0x7f}))
    }
    @Test fun motionFramingUsesDeclaredLengthEvenWithHeaderBytesInsidePayload(){
        val packet=frame(blob=blob().also{Aacp.header.copyInto(it,36)})
        val framer=AacpFramer()
        assertTrue(framer.feed(packet.copyOf(30)).isEmpty())
        val frames=framer.feed(packet.drop(30).toByteArray()+Aacp.control(13,listOf(2)))
        assertEquals(2,frames.size);assertContentEquals(packet,frames[0])
        assertTrue(Aacp.decode(frames[1]) is Event.Control)
    }
    @Test fun calibrationPreservesAnglesAndRejectsRepeatedOrSmallAxes(){
        fun q(axis:Int,angle:Double):Quaternion {
            val xyz=MutableList(3){0.0};xyz[axis]=sin(angle/2)
            return Quaternion(xyz[0],xyz[1],xyz[2],cos(angle/2))
        }
        val identity=Quaternion(0.0,0.0,0.0,1.0)
        val c=MotionCalibration()
        c.begin(identity);c.sample(q(0,0.01));assertFalse(c.finish())
        c.begin(identity);c.sample(q(2,0.5));assertTrue(c.finish())
        c.begin(identity);c.sample(q(2,0.4));assertFalse(c.finish())
        c.begin(identity);c.sample(q(0,0.4));assertTrue(c.finish())
        c.begin(identity);c.sample(q(1,0.4));assertTrue(c.finish())
        val matrix=c.matrix()!!
        assertEquals(listOf(0.0,0.0,0.5),MotionCalibration.apply(matrix,listOf(0.0,0.0,0.5)))
        val v=listOf(0.2,-0.4,0.7)
        assertEquals(MotionCalibration.norm(v),MotionCalibration.norm(MotionCalibration.apply(matrix,v)),1e-8)
    }
    @Test fun quaternionSignAndInverseDoNotCreateFalseHeadMovement(){
        val q=Quaternion(0.2,-0.1,0.3,sqrt(0.86)).normalized()
        assertTrue((q.inverse()*q).rotationVector().all{abs(it)<1e-8})
        val negative=Quaternion(-q.x,-q.y,-q.z,-q.w)
        q.rotationVector().zip(negative.rotationVector()).forEach{(a,b)->assertEquals(a,b,1e-8)}
    }
    @Test fun realServiceAcknowledgementIsNotAcceptedAsOrientation(){
        val ack=Aacp.header+byteArrayOf(0x17,0,0,0,16,0,8,0,8,6,16,1,0x4a,2,8,16)
        assertEquals(Event.MotionAcknowledgement(16),Aacp.decode(ack))
        assertNull(MotionCodec.decode(ack))
    }
    @Test fun rtBuddyDiscoveryFramesDoNotDiscardFollowingMotion(){
        val discovery=Aacp.header+byteArrayOf(0x17,0,0,2,0,0,3,0,1,2,3)
        val pose=frame()
        val frames=AacpFramer().feed(discovery+pose)
        assertEquals(2,frames.size)
        assertContentEquals(discovery,frames[0])
        assertTrue(Aacp.decode(frames[1]) is Event.Motion)
        val request=MotionCodec.descriptorRequest()
        assertEquals(request.size,MotionCodec.frameLength(request))
        assertContentEquals(byteArrayOf(8,0x80.toByte(),2,0x22,0),request.copyOfRange(12,request.size))
    }
    @Test fun serviceErrorsAndChecksummedWxFramesAreNotMistakenForSuccessfulPose(){
        val failed=Aacp.header+byteArrayOf(0x17,0,0,0,16,0,11,0,8,50,16,1,0x4a,5,8,6,16,0xc7.toByte(),5)
        assertEquals(Event.MotionAcknowledgement(6,711),Aacp.decode(failed))
        val pose=frame();val body=byteArrayOf(16,5)+pose.drop(12)+byteArrayOf(0,0)
        val withTrailer=pose.copyOf(12).also{it[10]=body.size.toByte()}+body
        assertNotNull(MotionCodec.decode(withTrailer))
        assertNull(MotionCodec.decode(withTrailer.copyOf().also{it[13]=1}))
        assertNull(MotionCodec.decode(frame(true,99)))
        assertNotNull(MotionCodec.decode(frame(true,99),expectedService=99))
        assertNull(MotionCodec.decode(frame(true,99),expectedService=16))
    }
    @Test fun sensorDiscoveryReadsOnlyBoundedDescriptorMetadata(){
        fun word(v:Long)=ByteArray(4){(v ushr (it*8)).toByte()}
        fun string(v:String):ByteArray {
            val b=v.toByteArray();return word(0x09000000L+b.size)+b+ByteArray((4-b.size%4)%4)
        }
        fun length(v:Int):ByteArray=if(v<128)byteArrayOf(v.toByte())else byteArrayOf(((v and 127) or 128).toByte(),(v ushr 7).toByte())
        val io=word(0xd3)+word(0x81000003L)+string("AccessoryService")+string("devmotion6")+
            string("MaxReportSize")+word(0x04000040)+word(191)+word(0)+string("ProtectedAccess")+word(0x8b000001L)
        val nested=byteArrayOf(8,15,18)+length(io.size)+io
        val body=byteArrayOf(0x2a)+length(nested.size)+nested
        val packet=Aacp.header+byteArrayOf(0x17,0,0,0,16,0,body.size.toByte(),(body.size ushr 8).toByte())+body
        assertEquals(listOf(MotionCodec.SensorService(15,"devmotion6",191,true)),MotionCodec.services(packet))
        for(n in io.indices)assertNull(IoSerial.dictionary(io.copyOf(n)))
        assertNull(IoSerial.dictionary(word(0xd3)+word(0x8100ffffL)))
        assertNull(IoSerial.dictionary(word(0xd3)+word(0x0c000000)))
        for(id in listOf(6,14,15,16,200)){
            val start=MotionCodec.subscription(id,20000,65535)
            val stop=MotionCodec.subscription(id,0,65536)
            assertEquals(start.size,MotionCodec.frameLength(start));assertEquals(stop.size,MotionCodec.frameLength(stop))
            assertFailsWith<IllegalArgumentException>{MotionCodec.subscription(id,1,10)}
        }
    }
}

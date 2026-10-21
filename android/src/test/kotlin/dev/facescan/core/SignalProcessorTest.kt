package dev.facescan.core
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
class SignalProcessorTest {
    @Test fun recoversKnownPulseFromGreenSignal() {
        val green=List(900){128+3*sin(2*PI*1.2*it/30)}
        assertEquals(72.0, SignalProcessor.livePulse(green), 2.0)
    }
    @Test fun rejectsFlatSignal() {
        assertEquals(0.0,SignalProcessor.livePulse(List(900){128.0}),0.0)
    }
    @Test fun derivedIndicatorsMatchReferenceFixture() {
        val green=List(900){128+3*sin(2*PI*1.2*it/30)+0.2*sin(2*PI*0.25*it/30)}
        val result=SignalProcessor.result(List(20){72.0},green,mapOf("age" to 40,"gender" to "male","heightCm" to 165.0,"weightKg" to 60.0))
        assertEquals(72,result["pulse"])
        assertEquals(25,result["hrv"])
        assertEquals(42,result["stress"])
        assertEquals(42,result["heartAge"])
        assertTrue((result["systolic"] as Int)>(result["diastolic"] as Int))
    }
    @Test(expected=IllegalArgumentException::class) fun doesNotProduceDefaultPulseWithoutSamples() {
        SignalProcessor.result(emptyList(),List(900){128.0},emptyMap<Any,Any>())
    }
}

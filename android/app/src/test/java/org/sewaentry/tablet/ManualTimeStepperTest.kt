package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sewaentry.tablet.ui.nextFiveMinuteValue
import org.sewaentry.tablet.ui.previousFiveMinuteValue

class ManualTimeStepperTest {
    @Test
    fun `minute increment advances to the next five minute mark`() {
        assertEquals(5, nextFiveMinuteValue(2))
        assertEquals(10, nextFiveMinuteValue(5))
        assertEquals(5, nextFiveMinuteValue(1))
        assertEquals(0, nextFiveMinuteValue(59))
    }

    @Test
    fun `minute decrement returns the previous five minute mark`() {
        assertEquals(0, previousFiveMinuteValue(2))
        assertEquals(0, previousFiveMinuteValue(5))
        assertEquals(5, previousFiveMinuteValue(9))
        assertEquals(55, previousFiveMinuteValue(0))
    }
}

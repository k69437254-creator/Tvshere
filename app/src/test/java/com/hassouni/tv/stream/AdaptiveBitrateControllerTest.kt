package com.hassouni.tv.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveBitrateControllerTest {
    @Test fun congestionReducesTargetQuickly() {
        val controller = AdaptiveBitrateController(2500, 300, 2500)
        assertEquals(2050, controller.update(congested = true, droppedFrames = 0))
    }

    @Test fun droppedFramesReduceTarget() {
        val controller = AdaptiveBitrateController(2000, 300, 2000)
        assertEquals(1640, controller.update(congested = false, droppedFrames = 3))
    }

    @Test fun stableConnectionGraduallyRaisesTarget() {
        val controller = AdaptiveBitrateController(1000, 300, 1500)
        repeat(11) { assertNull(controller.update(congested = false, droppedFrames = 0)) }
        assertEquals(1040, controller.update(congested = false, droppedFrames = 0))
    }

    @Test fun controllerNeverFallsBelowMinimum() {
        val controller = AdaptiveBitrateController(350, 300, 350)
        assertEquals(300, controller.update(congested = true, droppedFrames = 1))
        assertNull(controller.update(congested = true, droppedFrames = 2))
    }
}

package com.lasergrbl.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreModuleTest {

    @Test
    fun moduleSkeletonIsWired() {
        assertEquals("3.0.0-phase0", CoreModule.VERSION)
        assertTrue(CoreModule.PORTED_MODULES <= CoreModule.TOTAL_MODULES)
    }
}

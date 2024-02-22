package com.icuxika.vturbo

import com.icuxika.vturbo.app.App
import kotlin.test.Test
import kotlin.test.assertNotNull

class AppTest {
    @Test
    fun appHasAGreeting() {
        val classUnderTest = App()
        assertNotNull(classUnderTest.greeting, "app should have a greeting")
    }
}

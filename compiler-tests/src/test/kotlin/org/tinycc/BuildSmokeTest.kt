package org.tinycc

import kotlin.test.Test
import kotlin.test.assertEquals
import org.tinycc.core.BuildInfo

class BuildSmokeTest {
    @Test
    fun buildInfoDefinesTheJvmBaseline() {
        assertEquals("tinycc-jvm", BuildInfo.PROJECT_NAME)
        assertEquals(17, BuildInfo.JVM_TARGET)
    }
}

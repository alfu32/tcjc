package org.tinycc.core

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.api.config.BuildOptimization
import org.tinycc.api.config.KotlinBuildConfiguration
import org.tinycc.runtime.RuntimeLinkMode

class BuildConfigurationTest {
    @Test
    fun loadsTypedGradleAndCrossTargetConfiguration() {
        val root = Files.createTempDirectory("tcjc-config-")
        try {
            val configuration = KotlinBuildConfiguration.fromProperties(
                mapOf(
                    "tcjc.target" to "aarch64-linux",
                    "tcjc.optimization" to "release",
                    "tcjc.installPrefix" to "install",
                    "tcjc.outputDirectory" to "out",
                    "tcjc.sysroot" to "sysroot",
                    "tcjc.includePath" to "headers:generated",
                    "tcjc.libraryPath" to "libraries",
                    "tcjc.runtimeLinkMode" to "cross_target",
                    "tcjc.reproducible" to "true",
                ),
                root,
            )
            assertEquals("aarch64-linux", configuration.target)
            assertEquals(BuildOptimization.RELEASE, configuration.optimization)
            assertEquals(RuntimeLinkMode.CROSS_TARGET, configuration.runtimeLinkMode)
            assertEquals(root.resolve("install"), configuration.installPrefix)
            assertEquals(root.resolve("sysroot"), configuration.sysroot)
            assertEquals(listOf(root.resolve("headers"), root.resolve("generated")), configuration.includePaths)
            assertTrue(configuration.reproducible)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedConfigurationValues() {
        val root = Files.createTempDirectory("tcjc-config-")
        try {
            kotlin.test.assertFailsWith<IllegalStateException> {
                KotlinBuildConfiguration.fromProperties(mapOf("tcjc.runtimeLinkMode" to "native"), root)
            }
            kotlin.test.assertFailsWith<IllegalStateException> {
                KotlinBuildConfiguration.fromProperties(mapOf("tcjc.reproducible" to "maybe"), root)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

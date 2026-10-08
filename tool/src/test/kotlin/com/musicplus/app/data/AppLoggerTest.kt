package com.musicplus.app.data

import com.musicplus.app.BuildConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class AppLoggerTest {
    @Test
    fun beforeItIsStartedLoggingDoesNothingAndDoesNotFail() {
        AppLogger.d("Test", "nobody is listening")
        AppLogger.e("Test", "nobody is listening", RuntimeException("x"))
    }

    @Test
    fun theUnitTestsRunTheDebugVariantSoTheLogExistsThereAndOnlyThere() {
        // The release variant is what Light builds: it has no log. If this ever ran against it, this fails and says why.
        assertTrue(BuildConfig.DEBUG, "tests run the debug variant, the only one that logs")
        val dir = Files.createTempDirectory("applogger-test").toFile()
        try {
            AppLogger.init(dir)
            AppLogger.d("Test", "hello")
            val log = dir.resolve("logs/app.log")
            assertTrue(log.exists(), "a debug build writes the log")
            assertTrue("Test: hello" in log.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}

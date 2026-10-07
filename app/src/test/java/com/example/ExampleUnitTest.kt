package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun otaVersionComparison_isCorrect() {
    assertTrue(OtaUpdateManager.isNewerVersion("1.0.1", "1.0.0"))
    assertTrue(OtaUpdateManager.isNewerVersion("1.1.0", "1.0.9"))
    assertTrue(OtaUpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    assertFalse(OtaUpdateManager.isNewerVersion("1.0.0", "1.0.0"))
    assertFalse(OtaUpdateManager.isNewerVersion("1.0.0", "1.0.1"))
    assertFalse(OtaUpdateManager.isNewerVersion("0.9.9", "1.0.0"))
  }
}

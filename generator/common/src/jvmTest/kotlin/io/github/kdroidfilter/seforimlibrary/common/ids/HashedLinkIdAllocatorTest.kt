package io.github.kdroidfilter.seforimlibrary.common.ids

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashedLinkIdAllocatorTest {
    @Test
    fun linkIdsAreStableNegativeAndSensitiveToTheirNaturalKey() {
        val allocator = HashedLinkIdAllocator(mapOf("COMMENTARY" to 4L))

        val first = allocator.linkId(10, 20, 4)

        assertTrue(first < 0)
        assertEquals(first, allocator.linkId(10, 20, 4))
        assertNotEquals(first, allocator.linkId(20, 10, 4))
        assertNotEquals(first, allocator.linkId(10, 20, 5))
    }
}

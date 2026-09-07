package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Leaf homing must survive the empty book.
 *
 * Before the runtime QCF snapshot loads, the catalog is empty and the English
 * book built from it has no leaves. Every homing effect clamps through
 * [mushafLeafIndex]; the mode-switch one did not, and opening the English leaf
 * on an empty cache died with "Cannot coerce value to an empty range".
 */
class MushafLeafIndexTest {

    @Test
    fun `empty book parks every index on the blank paper`() {
        assertEquals(0, mushafLeafIndex(0, 0))
        assertEquals(0, mushafLeafIndex(5, 0))
        assertEquals(0, mushafLeafIndex(-3, 0))
    }

    @Test
    fun `populated book clamps normally`() {
        assertEquals(0, mushafLeafIndex(-1, 604))
        assertEquals(0, mushafLeafIndex(0, 604))
        assertEquals(603, mushafLeafIndex(603, 604))
        assertEquals(603, mushafLeafIndex(900, 604))
    }
}

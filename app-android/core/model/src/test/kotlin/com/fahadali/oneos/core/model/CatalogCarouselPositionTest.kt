package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogCarouselPositionTest {
    @Test
    fun emptyCatalogHasNoPosition() {
        assertNull(CatalogCarouselPosition.itemIndex(adapterPosition = 0, itemCount = 0))
        assertNull(CatalogCarouselPosition.itemIndex(adapterPosition = 4, itemCount = -1))
    }

    @Test
    fun infinitePositionsWrapAcrossCatalogEntries() {
        assertEquals(0, CatalogCarouselPosition.itemIndex(adapterPosition = 0, itemCount = 5) ?: -1)
        assertEquals(2, CatalogCarouselPosition.itemIndex(adapterPosition = 12, itemCount = 5) ?: -1)
        assertEquals(4, CatalogCarouselPosition.itemIndex(adapterPosition = -1, itemCount = 5) ?: -1)
    }
}

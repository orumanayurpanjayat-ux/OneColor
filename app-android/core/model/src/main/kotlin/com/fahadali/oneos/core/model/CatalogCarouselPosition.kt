package com.fahadali.oneos.core.model

/** Maps an infinite pager position to a catalog item without assuming the list is non-empty. */
object CatalogCarouselPosition {
    fun itemIndex(adapterPosition: Int, itemCount: Int): Int? {
        if (itemCount <= 0) return null
        return Math.floorMod(adapterPosition, itemCount)
    }
}

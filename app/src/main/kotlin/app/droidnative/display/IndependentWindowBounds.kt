package app.droidnative.display

/** Screen-space bounds. A display can have a nonzero origin. */
internal data class WindowArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

internal object IndependentWindowBounds {
    /** Keep a title strip of other windows visible by cascading new task bounds. */
    fun calculate(work: WindowArea, source: WindowArea?, bufferWidth: Int, bufferHeight: Int,
                  caption: Int, cascade: Int, id: Int): WindowArea {
        require(work.width > 0 && work.height > 0)
        // A dragged-out maximized tab can initially choose the screen's size;
        // an independently movable Android task must still have free space.
        val capW = (work.width * 5L / 6).toInt().coerceAtLeast(1)
        val capH = (work.height * 5L / 6).toInt().coerceAtLeast(1)
        val w = (bufferWidth.takeIf { it > 0 } ?: (work.width * 2L / 3).toInt()).coerceIn(1, capW)
        val h = (if (bufferHeight > 0) bufferHeight.toLong() + caption.coerceAtLeast(0)
                 else work.height * 2L / 3).coerceIn(1, capH.toLong()).toInt()
        val offset = cascade.coerceAtLeast(0) * (1 + Math.floorMod(id, 3))
        val x = ((source?.left ?: (work.left + (work.width - w) / 2)) + offset)
            .coerceIn(work.left, work.right - w)
        val y = ((source?.top ?: (work.top + (work.height - h) / 2)) + offset)
            .coerceIn(work.top, work.bottom - h)
        return WindowArea(x, y, x + w, y + h)
    }
}

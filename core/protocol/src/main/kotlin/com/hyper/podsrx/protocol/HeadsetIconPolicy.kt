package com.hyper.podsrx.protocol

/** Infers single-ear removal from the hub's current per-ear charging readings. */
class HeadsetIconPolicy {
    private var left = Position.UNKNOWN
    private var right = Position.UNKNOWN

    enum class Side(val component: Int) {
        PAIR(0), LEFT(4), RIGHT(2)
    }

    private enum class Position { UNKNOWN, CHARGING, REMOVED }

    fun update(readings: Map<Int, Reading>): Side {
        left = position(left, readings[Side.LEFT.component])
        right = position(right, readings[Side.RIGHT.component])
        return when {
            left == Position.REMOVED && right == Position.CHARGING -> Side.LEFT
            right == Position.REMOVED && left == Position.CHARGING -> Side.RIGHT
            else -> Side.PAIR
        }
    }

    fun reset() {
        left = Position.UNKNOWN
        right = Position.UNKNOWN
    }

    private fun position(previous: Position, reading: Reading?): Position = when {
        reading?.level == null || reading.level !in 0..100 -> Position.UNKNOWN
        reading.charging -> Position.CHARGING
        previous != Position.UNKNOWN -> Position.REMOVED
        else -> Position.UNKNOWN
    }
}

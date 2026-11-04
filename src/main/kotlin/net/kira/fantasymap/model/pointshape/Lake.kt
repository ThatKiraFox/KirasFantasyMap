package net.kira.fantasymap.model.pointshape

class Lake(points: FloatArray) : PointShape(points) {
    // Внутри ли точка (по ломаной из точек, для клика достаточно).
    fun contains(x: Float, y: Float): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        val points = points
        var inside = false
        val count = points.size / 2
        var previous = count - 1
        for (i in 0 until count) {
            val ax = points[2 * previous]
            val ay = points[2 * previous + 1]
            val bx = points[2 * i]
            val by = points[2 * i + 1]
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
            previous = i
        }
        return inside
    }
}
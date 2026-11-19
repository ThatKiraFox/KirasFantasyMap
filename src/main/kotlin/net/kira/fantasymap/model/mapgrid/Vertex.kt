package net.kira.fantasymap.model.mapgrid

class Vertex(val id: Int, var x: Float, var y: Float) {
    val edges = ArrayList<Edge>()
}
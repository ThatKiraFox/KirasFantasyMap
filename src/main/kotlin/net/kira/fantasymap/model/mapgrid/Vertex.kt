package net.kira.fantasymap.model.mapgrid

class Vertex(var x: Float, var y: Float) {
    val edges = ArrayList<Edge>()
}
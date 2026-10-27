package net.kira.fantasymap.model

enum class CityTag {
    CAPITAL,
    PORT,
    NORMAL,
    UNIVERSITY
}

class City(
    val id: Int,
    val name: String,
    val positionX: Float,
    val positionY: Float,
    val population: Int,
    val tags: List<CityTag>) {
}
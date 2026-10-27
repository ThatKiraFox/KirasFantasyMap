package net.kira.fantasymap.util

inline fun <T> timed(name: String, block: () -> T): T {
    val start = System.currentTimeMillis()
    val result = block()
    println("$name: ${System.currentTimeMillis() - start} ms")
    return result
}
package net.kira.fantasymap.util

//test function to check speed
inline fun <T> timed(name: String, block: () -> T): T {
    val start = System.currentTimeMillis()
    val result = block()
    println("$name: ${System.currentTimeMillis() - start} ms\n________________________________________________________________________________________________")
    return result
}
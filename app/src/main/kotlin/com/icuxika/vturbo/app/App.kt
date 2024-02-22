package com.icuxika.vturbo.app

class App {
    val greeting: String
        get() {
            return "Hello World!"
        }
}

fun main(args: Array<String>) {
    println(App().greeting)
}

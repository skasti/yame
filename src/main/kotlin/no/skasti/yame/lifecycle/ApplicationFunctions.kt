package no.skasti.yame.lifecycle

import no.skasti.yame.function.FunctionKey

object ApplicationFunctions {
    val Quit = FunctionKey<Unit, Unit>("application.quit")
}

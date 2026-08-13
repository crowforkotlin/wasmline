package bindings

/** Implements the typed Component export generated from `wit/world.wit`. */
object CalculatorImpl : Calculator {
    override fun add(left: Int, right: Int): Int = left + right
}

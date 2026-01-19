package com.mordecai.wasmline.cli.extensions

private const val CYAN = "\u001B[36m"
private const val NC = "\u001B[0m"
private const val LINE = "================================================="
private const val COLORED_BORDER = "$CYAN$LINE$NC\n"

fun printHeader(message: String) {
    val output = buildString(message.length + 128) {
        append(COLORED_BORDER)
        append(CYAN).append(message).append("       ").append(NC).append('\n')
        append(COLORED_BORDER)
    }
    print(output)
}
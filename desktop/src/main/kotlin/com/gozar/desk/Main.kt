package com.gozar.desk

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) {
    if (args.contains("--version")) {
        println("Gozar 1.7.1")
        return
    }
    if (args.contains("--selftest")) {
        println(SelfTest.run())
        return
    }
    if (args.contains("--proxy-test")) {
        println(ProxyTest.run())
        return
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Gozar",
            resizable = false,
            state = rememberWindowState(size = DpSize(420.dp, 820.dp))
        ) {
            GozarTheme { GozarApp() }
        }
    }
}

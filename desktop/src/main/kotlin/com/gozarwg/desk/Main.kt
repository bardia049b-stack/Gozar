package com.gozarwg.desk

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) {
    if (args.contains("--version")) {
        println("GozarWG 1.6.0")
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
            title = "GozarWG",
            state = rememberWindowState(size = DpSize(430.dp, 860.dp))
        ) {
            GozarTheme { GozarApp() }
        }
    }
}

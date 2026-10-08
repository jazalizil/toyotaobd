package com.example.toyotaobd.dev

/** Les builds release ne contiennent pas le simulateur : aucun adaptateur simulé. */
object DevDevices {
    val simulators: List<SimulatedDevice> = emptyList()
}

package com.example.toyotaobd.dev

import com.example.toyotaobd.obd.ObdTransport

/**
 * Faux adaptateur ELM327 utilisable sans Bluetooth ni véhicule. La liste est fournie par
 * `DevDevices`, qui n'en déclare que dans les builds debug (source set `src/debug`).
 */
class SimulatedDevice(val name: String, val description: String, val open: () -> ObdTransport)

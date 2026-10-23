package com.example.toyotaobd.obd

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Faux adaptateur ELM327 : [respond] reçoit chaque commande et renvoie la réponse (ou null : silence). */
private class FakeElm(
    private val replyDelayMs: Long = 0,
    private val respond: FakeElm.(String) -> String?,
) : ObdTransport {
    val commands = CopyOnWriteArrayList<String>()
    var headersOn = false
    var header = "7DF"

    private val queue = LinkedBlockingQueue<Int>()
    private val pending = StringBuilder()

    override suspend fun read(buffer: ByteArray): Int {
        val first = queue.take()
        if (first < 0) return -1
        buffer[0] = first.toByte()
        var n = 1
        while (n < buffer.size && (queue.peek() ?: -1) >= 0) buffer[n++] = queue.take().toByte()
        return n
    }

    override suspend fun write(data: ByteArray) {
        for (byte in data) {
            val c = byte.toInt().toChar()
            if (c != '\r') {
                pending.append(c)
                continue
            }
            val command = pending.toString().also { pending.clear() }
            commands += command
            when {
                command == "ATH1" -> headersOn = true
                command == "ATH0" -> headersOn = false
                command.startsWith("ATSH") -> header = command.removePrefix("ATSH")
            }
            val reply = respond(command) ?: return
            val emit = { "$reply\r\r>".forEach { queue.put(it.code) } }
            if (replyDelayMs > 0) {
                thread { Thread.sleep(replyDelayMs); emit() }
            } else {
                emit()
            }
        }
    }

    override fun close() = queue.put(-1)
}

/** Toyota hybride en CAN 11 bits : moteur (7E8) + calculateur hybride (7EA) + boîte (7E9). */
private fun toyotaHybrid() = FakeElm { command ->
    when {
        command == "ATZ" -> "ATZ\r\rELM327 v1.5"
        command == "ATDPN" -> "A6"
        command == "ATRV" -> "12.6V"
        command.startsWith("AT") -> "OK"
        command == "0100" && !headersOn -> "SEARCHING...\r41 00 BE 3F A8 13\r41 00 80 00 00 01"
        command == "0100" -> "7E8 06 41 00 BE 3F A8 13 00\r7EA 06 41 00 80 00 00 01 00"
        command == "0120" -> "7E8 06 41 20 80 00 00 00 00"
        command == "0902" -> "7E8 10 14 49 02 01 4A 54 44\r7E8 21 4B 42 32 30 55 39 33\r7E8 22 30 31 32 33 34 35 36"
        command == "010C" -> "7EA 04 41 0C 00 00 00 00 00\r7E8 04 41 0C 1A F8 00 00 00"
        command == "0101" -> "7E8 06 41 01 83 07 65 04 00\r7EA 06 41 01 00 00 00 00 00"
        command == "03" -> "7E8 04 43 01 01 33 00 00 00\r7EA 06 43 02 0A 80 01 71 00"
        command == "07" -> "7E8 02 47 00 00 00 00 00 00"
        command == "0A" -> "NO DATA"
        command == "04" -> "7E8 01 44 00 00 00 00 00 00"
        command == "221E1C" && header == "7E1" -> "7E9 05 62 1E 1C 19 00 00 00"
        command == "2182" && header == "7E1" -> "7E9 03 7F 21 31 00 00 00 00"
        else -> "NO DATA"
    }
}

class ObdClientTest {
    private val elms = mutableListOf<Elm327>()

    private fun connect(fake: FakeElm): ObdClient = ObdClient(Elm327(fake).also { elms += it })

    @After
    fun tearDown() = elms.forEach { it.close() }

    @Test
    fun `initialization detects CAN, chains supported PIDs and reads the VIN`() = runBlocking<Unit> {
        val fake = toyotaHybrid()
        val info = connect(fake).initialize(ProtocolChoice.TOYOTA_AUTO)

        assertEquals("ELM327 v1.5", info.adapterVersion)
        assertEquals(ObdProtocol.CAN_11_500, info.protocol)
        assertEquals("JTDKB20U930123456", info.vin)
        assertTrue(0x0C in info.supportedPids)
        assertTrue(0x21 in info.supportedPids)
        assertTrue("ATSPA6" in fake.commands)
        assertTrue("0120" in fake.commands)
        assertTrue("0140" !in fake.commands)
        // Les en-têtes ne sont activés qu'après la détection du protocole.
        assertTrue(fake.commands.indexOf("ATH1") > fake.commands.indexOf("ATDPN"))
    }

    @Test
    fun `live PID prefers the engine ECU`() = runBlocking<Unit> {
        val client = connect(toyotaHybrid())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)
        assertEquals(1726.0, client.readPid(Pids.RPM)!!, 0.001)
        assertNull(client.readPid(Pids.OIL_TEMP))
        assertEquals(12.6, client.readBatteryVoltage()!!, 0.001)
    }

    @Test
    fun `DTCs from several ECUs`() = runBlocking<Unit> {
        val client = connect(toyotaHybrid())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        assertEquals(MonitorStatus(milOn = true, dtcCount = 3), client.readMonitorStatus())
        assertEquals(
            listOf(Dtc("P0133", "7E8"), Dtc("P0A80", "7EA"), Dtc("P0171", "7EA")),
            client.readDtcs(DtcMode.STORED),
        )
        assertEquals(emptyList<Dtc>(), client.readDtcs(DtcMode.PENDING))
        assertEquals(emptyList<Dtc>(), client.readDtcs(DtcMode.PERMANENT))
        assertTrue(client.clearDtcs())
    }

    @Test
    fun `enhanced PID targets the ECU then restores broadcast`() = runBlocking<Unit> {
        val fake = toyotaHybrid()
        val client = connect(fake)
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        val reading = client.readEnhanced(Toyota.PRESETS.first { it.command == "221E1C" })
        assertEquals(80.0, reading.value!!, 0.001)
        assertEquals(listOf(0x19, 0x00), reading.payload)

        val after = fake.commands.drop(fake.commands.indexOf("221E1C"))
        assertEquals(listOf("221E1C", "ATSH7DF"), after)
        // 7E1 → 7E9 passe le filtre par défaut (la recherche des données hybrides, elle, a filtré 7DA et 74F).
        assertTrue(fake.commands.drop(fake.commands.indexOf("ATSH7E1")).none { it.startsWith("ATCRA") })
        assertEquals("7DF", fake.header)

        val negative = client.readEnhanced(Toyota.PRESETS.first { it.command == "2182" })
        assertTrue(negative.error!!.contains("0x31"))
        assertEquals("7DF", fake.header)

        // Le tableau de bord fonctionne toujours après une lecture constructeur.
        assertEquals(1726.0, client.readPid(Pids.RPM)!!, 0.001)
    }

    @Test
    fun `ECU outside 7E0-7E7 gets a receive filter and flow control, reset with a fallback`() = runBlocking<Unit> {
        val fake = FakeElm { command ->
            when {
                command == "ATDPN" -> "6"
                command == "ATCRA" -> "?"
                command.startsWith("AT") -> "OK"
                command == "0100" && !headersOn -> "41 00 BE 3F A8 13"
                command == "0100" -> "7E8 06 41 00 BE 3F A8 13 00"
                command == "2101" && header == "7B0" -> "7B8 03 61 01 2A 00 00 00 00"
                else -> "NO DATA"
            }
        }
        val client = connect(fake)
        client.initialize(ProtocolChoice.CAN_11_500)

        val abs = EnhancedPid("abs", "ABS", "7B0", "2101", "A", "")
        assertEquals(42.0, client.readEnhanced(abs).value!!, 0.001)
        // La recherche des données constructeur a déjà interrogé 7B0 (angle du volant) : dernière lecture seulement.
        val after = fake.commands.drop(fake.commands.lastIndexOf("ATSH7B0"))
        assertEquals(
            listOf("ATSH7B0", "ATCRA7B8", "ATFCSH7B0", "ATFCSD300000", "ATFCSM1", "2101", "ATSH7DF", "ATCRA", "ATAR", "ATFCSM0"),
            after,
        )
    }

    @Test
    fun `late reply of a cancelled command is not given to the next one`() = runBlocking<Unit> {
        val fake = FakeElm(replyDelayMs = 150) { command ->
            when (command) {
                "010C" -> "41 0C 1A F8"
                "0101" -> "41 01 83 07 65 04"
                else -> "OK"
            }
        }
        val elm = Elm327(fake).also { elms += it }

        val pending = launch { elm.send("010C") }
        delay(20)
        pending.cancelAndJoin()

        assertTrue(elm.send("0101").contains("41 01"))
    }

    @Test
    fun `vehicle silent gives a clear error`() = runBlocking<Unit> {
        val client = connect(
            FakeElm { command ->
                when {
                    command == "ATZ" -> "ELM327 v2.1"
                    command.startsWith("AT") -> "OK"
                    else -> "SEARCHING...\rUNABLE TO CONNECT"
                }
            },
        )
        val error = runCatching { client.initialize(ProtocolChoice.AUTO) }.exceptionOrNull()
        assertTrue(error is ObdException)
        assertTrue(error!!.message!!.contains("UNABLE TO CONNECT"))
    }

    @Test
    fun `ISO 9141 vehicle with legacy headers`() = runBlocking<Unit> {
        val fake = FakeElm { command ->
            when {
                command == "ATZ" -> "ELM327 v1.5"
                command == "ATDPN" -> "A3"
                command.startsWith("AT") -> "OK"
                command == "0100" && !headersOn -> "BUS INIT: ...OK\r41 00 BE 1F B8 10"
                command == "0100" -> "48 6B 10 41 00 BE 1F B8 10 AA"
                command == "010D" -> "48 6B 10 41 0D 32 F3"
                command == "03" -> "48 6B 10 43 01 33 04 20 00 00 C4"
                else -> "NO DATA"
            }
        }
        val client = connect(fake)
        val info = client.initialize(ProtocolChoice.ISO_9141)

        assertEquals(ObdProtocol.ISO_9141_2, info.protocol)
        assertEquals(50.0, client.readPid(Pids.SPEED)!!, 0.001)
        assertEquals(listOf(Dtc("P0133", "10"), Dtc("P0420", "10")), client.readDtcs(DtcMode.STORED))
        assertThrows(ObdException::class.java) {
            runBlocking { client.readEnhanced(Toyota.PRESETS.first()) }
        }
    }

    @Test
    fun `timeout keeps the link, closing the transport reports a disconnect`() = runBlocking<Unit> {
        val fake = FakeElm { command -> if (command == "SILENCE") null else "OK" }
        val elm = Elm327(fake).also { elms += it }

        assertThrows(ElmTimeoutException::class.java) { runBlocking { elm.send("SILENCE", 200) } }
        assertEquals("OK", elm.send("ATI").trim())

        fake.close()
        val cause = withTimeout(2_000) { elm.disconnected.await() }
        assertTrue(cause is TransportIOException)
        assertThrows(TransportIOException::class.java) { runBlocking { elm.send("ATI") } }
    }
}

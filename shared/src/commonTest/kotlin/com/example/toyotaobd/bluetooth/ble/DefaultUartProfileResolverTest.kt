package com.example.toyotaobd.bluetooth.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class DefaultUartProfileResolverTest {

    private val resolver = DefaultUartProfileResolver

    private fun uuid(short: Long): Uuid {
        val hex = (short and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
        return Uuid.parse("$hex-0000-1000-8000-00805F9B34FB")
    }

    private fun char(
        uuid: Uuid,
        notify: Boolean = false,
        write: Boolean = false,
        writeWithoutResponse: Boolean = false,
    ) = GattCharacteristic(
        uuid = uuid,
        canNotify = notify,
        canWriteWithResponse = write,
        canWriteWithoutResponse = writeWithoutResponse,
    )

    private fun service(uuid: Uuid, vararg characteristics: GattCharacteristic) =
        GattService(uuid = uuid, characteristics = characteristics.toList())

    @Test
    fun `resolves Nordic UART Service`() {
        val services = listOf(
            service(
                uuid(0x6E400001),
                char(uuid(0x6E400002), write = true),
                char(uuid(0x6E400003), notify = true),
            ),
        )
        val resolved = resolver.resolve(services)
        assertEquals(uuid(0x6E400001), resolved?.service)
        assertEquals(uuid(0x6E400003), resolved?.notify)
        assertEquals(uuid(0x6E400002), resolved?.write)
        assertEquals(UartWriteType.WITH_RESPONSE, resolved?.writeType)
    }

    @Test
    fun `resolves FFF0 clone in both orientations`() {
        val fff1First = listOf(
            service(
                uuid(0xFFF0),
                char(uuid(0xFFF1), notify = true),
                char(uuid(0xFFF2), write = true),
            ),
        )
        val fff2First = listOf(
            service(
                uuid(0xFFF0),
                char(uuid(0xFFF2), notify = true),
                char(uuid(0xFFF1), write = true),
            ),
        )
        assertEquals(uuid(0xFFF1), resolver.resolve(fff1First)?.notify)
        assertEquals(uuid(0xFFF2), resolver.resolve(fff1First)?.write)
        assertEquals(uuid(0xFFF2), resolver.resolve(fff2First)?.notify)
        assertEquals(uuid(0xFFF1), resolver.resolve(fff2First)?.write)
    }

    @Test
    fun `resolves HM-10 single characteristic for notify and write`() {
        val services = listOf(
            service(uuid(0xFFE0), char(uuid(0xFFE1), notify = true, writeWithoutResponse = true)),
        )
        val resolved = resolver.resolve(services)
        assertEquals(uuid(0xFFE1), resolved?.notify)
        assertEquals(uuid(0xFFE1), resolved?.write)
        // Pas de propriété « write » : écriture sans accusé de réception.
        assertEquals(UartWriteType.WITHOUT_RESPONSE, resolved?.writeType)
    }

    @Test
    fun `prefers with response when characteristic supports both`() {
        val services = listOf(
            service(
                uuid(0xFFF0),
                char(uuid(0xFFF1), notify = true),
                char(uuid(0xFFF2), write = true, writeWithoutResponse = true),
            ),
        )
        assertEquals(UartWriteType.WITH_RESPONSE, resolver.resolve(services)?.writeType)
    }

    @Test
    fun `returns null when service is missing`() {
        val services = listOf(
            service(uuid(0x180F), char(uuid(0x2A19), notify = true)),
        )
        assertNull(resolver.resolve(services))
    }

    @Test
    fun `returns null when notify characteristic cannot notify`() {
        val services = listOf(
            service(
                uuid(0xFFF0),
                char(uuid(0xFFF1), write = true), // pas de propriété notify
                char(uuid(0xFFF2), write = true),
            ),
        )
        assertNull(resolver.resolve(services))
    }

    @Test
    fun `returns null when write characteristic cannot write`() {
        val services = listOf(
            service(
                uuid(0xFFF0),
                char(uuid(0xFFF1), notify = true),
                char(uuid(0xFFF2), notify = true), // pas de propriété write
            ),
        )
        assertNull(resolver.resolve(services))
    }

    @Test
    fun `skips candidate with missing characteristic and uses next candidate`() {
        // FFF0 présent mais incomplet (pas FFF2) : on retombe sur le profil HM-10.
        val services = listOf(
            service(
                uuid(0xFFF0),
                char(uuid(0xFFF1), notify = true),
            ),
            service(uuid(0xFFE0), char(uuid(0xFFE1), notify = true, write = true)),
        )
        val resolved = resolver.resolve(services)
        assertEquals(uuid(0xFFE0), resolved?.service)
        assertEquals(uuid(0xFFE1), resolved?.write)
    }

    @Test
    fun `resolves Veepeak Texas Instruments UART by full uuid`() {
        val serviceUuid = Uuid.parse("F0000110-0451-4000-B000-000000000000")
        val notifyUuid = Uuid.parse("F0000111-0451-4000-B000-000000000000")
        val writeUuid = Uuid.parse("F0000112-0451-4000-B000-000000000000")
        val services = listOf(
            service(
                serviceUuid,
                char(writeUuid, write = true),
                char(notifyUuid, notify = true),
            ),
        )
        val resolved = resolver.resolve(services)
        assertEquals(serviceUuid, resolved?.service)
        assertEquals(notifyUuid, resolved?.notify)
        assertEquals(writeUuid, resolved?.write)
    }

    @Test
    fun `uuid comparison is case insensitive`() {
        val services = listOf(
            service(
                Uuid.parse("0000fff0-0000-1000-8000-00805f9b34fb"), // minuscules
                char(Uuid.parse("0000fff1-0000-1000-8000-00805f9b34fb"), notify = true),
                char(Uuid.parse("0000fff2-0000-1000-8000-00805f9b34fb"), write = true),
            ),
        )
        assertEquals(uuid(0xFFF0), resolver.resolve(services)?.service)
    }
}

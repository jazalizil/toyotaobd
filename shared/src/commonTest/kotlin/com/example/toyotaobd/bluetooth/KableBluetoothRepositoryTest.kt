package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ble.BleAdapter
import com.example.toyotaobd.bluetooth.ble.BlePeripheral
import com.example.toyotaobd.bluetooth.ports.BluetoothPermissions
import com.example.toyotaobd.bluetooth.ports.BluetoothRadio
import com.example.toyotaobd.bluetooth.ports.PairedDevicesProvider
import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class KableBluetoothRepositoryTest {

    // --- Fakes des ports (inversion de dépendances : aucun Kable dans ces tests) -----------

    private class FakeRadio(
        override val isAvailable: Boolean = true,
        initialEnabled: Boolean = true,
    ) : BluetoothRadio {
        private val _state = MutableStateFlow(
            if (initialEnabled) BluetoothRadio.State.ENABLED else BluetoothRadio.State.DISABLED,
        )
        override val state: StateFlow<BluetoothRadio.State> = _state.asStateFlow()

        fun setEnabled(enabled: Boolean) {
            _state.value = if (enabled) BluetoothRadio.State.ENABLED else BluetoothRadio.State.DISABLED
        }
    }

    private class FakePermissions(
        private val granted: Boolean,
        override val required: Array<String> = arrayOf("BLUETOOTH_SCAN"),
    ) : BluetoothPermissions {
        override fun isGranted(): Boolean = granted
    }

    private class FakePaired(private val devices: List<BtDevice>) : PairedDevicesProvider {
        override fun paired(): List<BtDevice> = devices
    }

    private object FakeTransport : ObdTransport {
        override suspend fun read(buffer: ByteArray): Int = -1
        override suspend fun write(data: ByteArray) = Unit
        override fun close() = Unit
    }

    private class FakePeripheral(
        override val device: BtDevice,
        val transport: ObdTransport = FakeTransport,
    ) : BlePeripheral {
        var connects = 0
            private set

        override suspend fun connect(): ObdTransport {
            connects++
            return transport
        }
    }

    /** Scan contrôlé : émet [emitted] à chaque collecte, et compte les scans lancés. */
    private class FakeAdapter(private val emitted: List<BlePeripheral>) : BleAdapter {
        var scanCount = 0
            private set

        override fun scan(): Flow<BlePeripheral> {
            scanCount++
            return flowOf(*emitted.toTypedArray())
        }
    }

    private fun repo(
        ble: BleAdapter,
        radio: BluetoothRadio = FakeRadio(),
        permissions: BluetoothPermissions = FakePermissions(granted = true),
        paired: PairedDevicesProvider = FakePaired(emptyList()),
    ) = KableBluetoothRepository(
        ble = ble,
        radio = radio,
        permissions = permissions,
        pairedDevices = paired,
        scanTimeout = 50.milliseconds,
    )

    private fun device(name: String, address: String, bonded: Boolean = false) =
        BtDevice(name, address, bonded)

    // --- Délégation aux ports ---------------------------------------------------------------

    @Test
    fun `delegates radio and permissions to ports`() = runTest {
        val r = repo(
            ble = FakeAdapter(emptyList()),
            radio = FakeRadio(isAvailable = false, initialEnabled = false),
            permissions = FakePermissions(granted = false, required = arrayOf("A", "B")),
        )
        assertFalse(r.isAvailable)
        assertFalse(r.isEnabled)
        assertFalse(r.hasPermissions())
        assertEquals(listOf("A", "B"), r.requiredPermissions.toList())
    }

    @Test
    fun `radioState is a reactive flow that follows the radio port`() = runTest {
        val radio = FakeRadio(initialEnabled = false)
        val r = repo(ble = FakeAdapter(emptyList()), radio = radio)
        assertEquals(BluetoothRadio.State.DISABLED, r.radioState.value)
        assertFalse(r.isEnabled)

        // Le Bluetooth est allumé depuis les réglages système : le flux réagit.
        val enabledFlow = r.radioState.map { it == BluetoothRadio.State.ENABLED }
        assertFalse(enabledFlow.first())
        radio.setEnabled(true)
        assertEquals(BluetoothRadio.State.ENABLED, r.radioState.value)
        assertTrue(r.isEnabled)
        assertTrue(enabledFlow.first())
    }

    @Test
    fun `paired devices are sorted OBD first then by name`() = runTest {
        val paired = FakePaired(
            listOf(
                device("Divers", "00:00:00:00:00:03"),
                device("ELM327", "00:00:00:00:00:01", bonded = true),
                device("OBDLink", "00:00:00:00:00:02", bonded = true),
            ),
        )
        val r = repo(ble = FakeAdapter(emptyList()), paired = paired)
        assertEquals(
            listOf("ELM327", "OBDLink", "Divers"),
            r.pairedDevices().map { it.name },
        )
    }

    // --- Découverte et cache ----------------------------------------------------------------

    @Test
    fun `discover emits scanned devices and caches them for connect`() = runTest {
        val peripheral = FakePeripheral(device("ELM327", "AA:BB:CC:DD:EE:FF"))
        val adapter = FakeAdapter(listOf(peripheral))
        val r = repo(ble = adapter)

        val discovered = mutableListOf<BtDevice>()
        r.discover().collect { discovered.add(it) }
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), discovered.map { it.address })
        assertEquals(1, adapter.scanCount)

        // La connexion réutilise le périphérique mis en cache : pas de nouveau scan.
        val transport = r.connect("AA:BB:CC:DD:EE:FF")
        assertSame(FakeTransport, transport)
        assertEquals(1, peripheral.connects)
        assertEquals(1, adapter.scanCount)
    }

    @Test
    fun `discover marks bonded the devices present in the paired list`() = runTest {
        val scanned = FakePeripheral(device("ELM327", "AA:BB:CC:DD:EE:FF"))
        val r = repo(
            ble = FakeAdapter(listOf(scanned)),
            paired = FakePaired(listOf(device("ELM327", "AA:BB:CC:DD:EE:FF", bonded = true))),
        )
        val discovered = mutableListOf<BtDevice>()
        r.discover().collect { discovered.add(it) }
        assertTrue(discovered.single().bonded)
    }

    @Test
    fun `connect falls back to a bounded scan when the device is not cached`() = runTest {
        val peripheral = FakePeripheral(device("VGate", "11:22:33:44:55:66"))
        val adapter = FakeAdapter(listOf(peripheral))
        val r = repo(ble = adapter)

        val transport = r.connect("11:22:33:44:55:66")
        assertSame(FakeTransport, transport)
        assertEquals(1, peripheral.connects)
        assertEquals(1, adapter.scanCount)
    }

    @Test
    fun `connect throws TransportIOException when the device never appears`() = runTest {
        val r = repo(ble = FakeAdapter(emptyList()))
        val error = assertFailsWith<TransportIOException> {
            r.connect("00:00:00:00:00:99")
        }
        assertTrue(error.message!!.contains("00:00:00:00:00:99"))
    }
}

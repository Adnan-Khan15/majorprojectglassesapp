package com.smartglasses.app.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.PhyRequest
import no.nordicsemi.android.ble.ktx.suspend

/**
 * GATT client for the glasses. All GATT operations go through Nordic's request
 * queue, so there is never more than one in flight. The v2 characteristics
 * (COMMAND / EVENT / AUDIO_*) are optional so older v1 firmware still connects.
 */
class GlassesBleManager(
    context: Context,
    private val onControl: (ByteArray) -> Unit,
    private val onImageChunk: (ByteArray) -> Unit,
    private val onEvent: (ByteArray) -> Unit,
    private val onAudioUp: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
) : BleManager(context) {

    private var control: BluetoothGattCharacteristic? = null
    private var imageData: BluetoothGattCharacteristic? = null
    private var resultText: BluetoothGattCharacteristic? = null
    private var command: BluetoothGattCharacteristic? = null
    private var event: BluetoothGattCharacteristic? = null
    private var audioUp: BluetoothGattCharacteristic? = null
    private var audioDown: BluetoothGattCharacteristic? = null

    var negotiatedMtu: Int = 23
        private set

    /** True when the firmware speaks protocol v2 (has COMMAND + EVENT). */
    val hasV2: Boolean get() = command != null && event != null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(GlassesProtocol.SERVICE) ?: return false
        control = service.getCharacteristic(GlassesProtocol.CONTROL)
        imageData = service.getCharacteristic(GlassesProtocol.IMAGE_DATA)
        resultText = service.getCharacteristic(GlassesProtocol.RESULT_TEXT)
        command = service.getCharacteristic(GlassesProtocol.COMMAND)
        event = service.getCharacteristic(GlassesProtocol.EVENT)
        audioUp = service.getCharacteristic(GlassesProtocol.AUDIO_UP)
        audioDown = service.getCharacteristic(GlassesProtocol.AUDIO_DOWN)
        return control != null && imageData != null && resultText != null
    }

    override fun initialize() {
        requestMtu(GlassesProtocol.MAX_MTU)
            .with { _, mtu -> negotiatedMtu = mtu; onLog("MTU negotiated: $mtu") }
            .fail { _, status -> onLog("MTU request failed ($status), staying at 23") }
            .enqueue()
        requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH).enqueue()
        setIndicationCallback(control).with { _, data -> data.value?.let(onControl) }
        setIndicationCallback(imageData).with { _, data -> data.value?.let(onImageChunk) }
        enableIndications(control).enqueue()
        enableIndications(imageData).enqueue()
        if (event != null) {
            setNotificationCallback(event).with { _, data -> data.value?.let(onEvent) }
            enableNotifications(event).enqueue()
        }
        if (audioUp != null) {
            setNotificationCallback(audioUp).with { _, data -> data.value?.let(onAudioUp) }
            enableNotifications(audioUp).enqueue()
        }
        onLog(if (hasV2) "Glasses firmware speaks protocol v2" else "Glasses firmware is v1 (photos only; update the firmware for speaker/mic)")
    }

    override fun onServicesInvalidated() {
        control = null; imageData = null; resultText = null
        command = null; event = null; audioUp = null; audioDown = null
    }

    suspend fun writeResultText(text: String) {
        writeCharacteristic(resultText, text.toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .split().suspend()
    }

    suspend fun writeCommand(bytes: ByteArray) {
        val c = command ?: error("Glasses firmware has no COMMAND characteristic (v1)")
        writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
    }

    /** Write without response; Nordic's queue paces these by the stack's flow control. */
    suspend fun writeAudioDown(frame: ByteArray) {
        val c = audioDown ?: error("Glasses firmware has no AUDIO_DOWN characteristic (v1)")
        writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
    }

    /** Asks for the 2M PHY (throughput experiments). The phone/board may refuse. */
    suspend fun request2mPhy(enable: Boolean) {
        val mask = if (enable) PhyRequest.PHY_LE_2M_MASK else PhyRequest.PHY_LE_1M_MASK
        setPreferredPhy(mask, mask, PhyRequest.PHY_OPTION_NO_PREFERRED)
            .with { _, tx, rx -> onLog("PHY now tx=$tx rx=$rx (1=1M, 2=2M)") }
            .fail { _, status -> onLog("PHY request failed ($status)") }
            .suspend()
    }

    override fun log(priority: Int, message: String) {
        if (priority >= android.util.Log.INFO) android.util.Log.println(priority, "GlassesBle", message)
    }
}

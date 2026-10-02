package com.smartglasses.app.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.ktx.suspend

/**
 * GATT client for the glasses. All GATT operations go through Nordic's request
 * queue, so there is never more than one in flight.
 */
class GlassesBleManager(
    context: Context,
    private val onControl: (ByteArray) -> Unit,
    private val onImageChunk: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
) : BleManager(context) {

    private var control: BluetoothGattCharacteristic? = null
    private var imageData: BluetoothGattCharacteristic? = null
    private var resultText: BluetoothGattCharacteristic? = null

    var negotiatedMtu: Int = 23
        private set

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(GlassesProtocol.SERVICE) ?: return false
        control = service.getCharacteristic(GlassesProtocol.CONTROL)
        imageData = service.getCharacteristic(GlassesProtocol.IMAGE_DATA)
        resultText = service.getCharacteristic(GlassesProtocol.RESULT_TEXT)
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
    }

    override fun onServicesInvalidated() {
        control = null
        imageData = null
        resultText = null
    }

    suspend fun writeResultText(text: String) {
        writeCharacteristic(
            resultText,
            text.toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ).split().suspend()
    }

    override fun log(priority: Int, message: String) {
        if (priority >= android.util.Log.INFO) android.util.Log.println(priority, "GlassesBle", message)
    }
}

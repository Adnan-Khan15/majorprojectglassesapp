package com.smartglasses.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

sealed interface LinkState {
    data object Idle : LinkState
    data object BluetoothOff : LinkState
    data object Scanning : LinkState
    data class Connecting(val name: String) : LinkState
    data class Connected(val name: String, val mtu: Int) : LinkState
    data class Reconnecting(val reason: String, val inSeconds: Int) : LinkState
}

data class LogLine(val time: String, val text: String)

/**
 * Owns the glasses connection for the whole process lifetime (not tied to any
 * Activity), so rotation/backgrounding never drops it. Runs a supervise loop:
 * scan -> connect -> wait for disconnect -> back off -> repeat.
 */
@SuppressLint("MissingPermission") // only started after permissions are granted
class GlassesLink(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private fun setState(s: LinkState) {
        // Countdown ticks would flood the log; record only the first tick of each wait.
        val prev = _state.value
        if (s != prev && !(s is LinkState.Reconnecting && prev is LinkState.Reconnecting)) log("State: $s")
        _state.value = s
    }

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _captures = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 256)
    /** Photos arriving from the glasses (header, progress, completed image or failure). */
    val captures: SharedFlow<CaptureEvent> = _captures.asSharedFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 256)
    /** Protocol v2 events (button, ToF, playback, credits, errors, ...). */
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _audioUp = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2048)
    /** Raw AUDIO_UP frames (mic audio, throughput test payload). */
    val audioUp: SharedFlow<ByteArray> = _audioUp.asSharedFlow()

    private val _caps = MutableStateFlow<Frames.Caps?>(null)
    /** What the connected glasses can do (null until the v2 handshake answers, or v1 firmware). */
    val caps: StateFlow<Frames.Caps?> = _caps.asStateFlow()

    /** ToF results by image id: the board sends them after the image (protocol v2). */
    private val tofWaiters = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<TofReading>>()
    private val resendRequested = java.util.Collections.synchronizedSet(HashSet<Int>())

    private val assembler = ImageAssembler { SystemClock.elapsedRealtime() }
    private var stallWatchdog: Job? = null

    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
    private var loop: Job? = null
    private var manager: GlassesBleManager? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { superviseLoop() }
    }

    private suspend fun superviseLoop() {
        var failures = 0
        while (scope.isActive) {
            if (adapter?.isEnabled != true) {
                setState(LinkState.BluetoothOff)
                delay(1000)
                continue
            }
            setState(LinkState.Scanning)
            val device = withTimeoutOrNull(SCAN_WINDOW_MS) { scanForGlasses() }
            if (device == null) {
                failures++
                backOff("Glasses not found", failures)
                continue
            }
            val name = device.name ?: GlassesProtocol.DEVICE_NAME
            setState(LinkState.Connecting(name))
            val mgr = GlassesBleManager(context, ::onControl, ::onImageChunk, ::onEvent, ::onAudioUp, ::log)
            manager = mgr
            try {
                mgr.connect(device)
                    .useAutoConnect(false)
                    .retry(3, 300)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .suspend()
                failures = 0
                setState(LinkState.Connected(name, mgr.negotiatedMtu))
                log("Connected to $name (${device.address}), MTU ${mgr.negotiatedMtu}")
                configured = false
                if (mgr.hasV2) sendCommand(Frames.cmd(Frames.CMD_GET_CAPS))
                mgr.stateAsFlow().first { it is ConnectionState.Disconnected }
                log("Glasses disconnected")
                _caps.value = null
                emitAll(assembler.abort("glasses disconnected"))
            } catch (e: Exception) {
                failures++
                log("Connect failed: ${e.message ?: e::class.simpleName}")
            } finally {
                manager = null
                mgr.close()
            }
            backOff("Connection lost", failures)
        }
    }

    private suspend fun backOff(reason: String, failures: Int) {
        // Short first retry; capped so the demo recovers quickly. Android also
        // rate-limits >5 scan starts per 30 s, so never go below 2 s.
        val seconds = (2 * failures.coerceAtLeast(1)).coerceAtMost(10)
        for (s in seconds downTo 1) {
            setState(LinkState.Reconnecting(reason, s))
            delay(1000)
        }
    }

    private suspend fun scanForGlasses(): BluetoothDevice =
        suspendCancellableCoroutine { cont ->
            val scanner = adapter.bluetoothLeScanner
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    scanner.stopScan(this)
                    if (cont.isActive) cont.resume(result.device)
                }
                override fun onScanFailed(errorCode: Int) {
                    log("Scan failed (code $errorCode)")
                }
            }
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(GlassesProtocol.SERVICE))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, callback)
            cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
        }

    // ---- Captures: CONTROL header + IMAGE_DATA chunks -> one JPEG -------------
    // Nordic delivers indications on one callback thread, in order.

    private fun onControl(bytes: ByteArray) {
        val events = assembler.onHeader(bytes)
        events.filterIsInstance<CaptureEvent.Started>().forEach {
            log("← CONTROL header: photo ${it.imageId?.let { id -> "$id, " } ?: ""}${it.totalBytes} bytes" +
                (it.v1Tof?.let { t -> ", distance ${if (t.isValid) "${t.medianMm} mm" else "no reading"} (v1 firmware)" } ?: ""))
        }
        emitAll(events)
        armStallWatchdog()
    }

    private fun onImageChunk(bytes: ByteArray) {
        val events = assembler.onChunk(bytes)
        emitAll(events)
        if (assembler.inProgress) armStallWatchdog() else stallWatchdog?.cancel()
    }

    /** Fails the transfer if the glasses stop sending mid-photo, instead of waiting forever. */
    private fun armStallWatchdog() {
        stallWatchdog?.cancel()
        stallWatchdog = scope.launch {
            delay(CHUNK_STALL_TIMEOUT_MS)
            emitAll(assembler.abort("no data from the glasses for ${CHUNK_STALL_TIMEOUT_MS / 1000} s"))
        }
    }

    private fun emitAll(events: List<CaptureEvent>) {
        for (e in events) {
            when (e) {
                is CaptureEvent.Completed -> log("← IMAGE_DATA complete: ${e.jpeg.size} bytes in ${e.transferMs} ms " +
                    "(%.1f KB/s)".format(e.jpeg.size / 1.024 / e.transferMs.coerceAtLeast(1)))
                is CaptureEvent.Failed -> {
                    log("Photo transfer failed: ${e.reason}")
                    // A checksum mismatch with every byte present: ask once for the same photo again.
                    if (e.resendable && e.imageId != null && resendRequested.add(e.imageId)) {
                        log("→ asking the glasses to resend photo ${e.imageId}")
                        scope.launch { sendCommand(Frames.cmdU16(Frames.CMD_RESEND_IMAGE, e.imageId)) }
                        continue  // not a failure yet: the resend will arrive as a new capture
                    }
                }
                else -> Unit
            }
            _captures.tryEmit(e)
        }
    }

    private fun onEvent(bytes: ByteArray) {
        val e = Frames.parseEvent(bytes) ?: return log("Malformed EVENT (${bytes.size} bytes)")
        when (e) {
            is GlassesEvent.CapsEvent -> {
                _caps.value = e.caps
                if (!configured) {
                    configured = true
                    onFirstCaps?.let { hook -> scope.launch { hook(e.caps) } }
                }
                log("Glasses firmware ${e.caps.firmware}, protocol v${e.caps.protocol}: camera=${e.caps.hasCamera} " +
                    "ToF=${e.caps.hasTof} speaker=${e.caps.hasSpeaker} mic=${e.caps.hasMic}, reset reason ${e.caps.resetReason}" +
                    if (e.caps.brownoutReset) " (BROWNOUT)" else "")
            }
            is GlassesEvent.Tof -> {
                log("← ToF for image ${e.imageId}: ${e.reading.medianMm} mm, ${e.reading.validCount}/${e.reading.sampleCount} valid, " +
                    "spread ${e.reading.spreadMm} mm, flags 0x%02x".format(e.reading.flags))
                tofWaiters.getOrPut(e.imageId) { kotlinx.coroutines.CompletableDeferred() }.complete(e.reading)
            }
            is GlassesEvent.Error -> log("← Glasses error ${e.code}: ${e.text}")
            is GlassesEvent.PhotoCaptured -> log("← Photo ${e.imageId} captured: ${e.size} bytes, ${e.pressToCaptureMs} ms after press" +
                if (e.rejectedFirstLen > 0) " (first frame was only ${e.rejectedFirstLen} bytes, retried)" else "")
            is GlassesEvent.ImageAborted -> emitAll(assembler.abort("glasses aborted the photo"))
            is GlassesEvent.Credits, is GlassesEvent.MicLevel -> Unit  // too frequent to log
            else -> log("← $e")
        }
        _events.tryEmit(e)
    }

    private fun onAudioUp(bytes: ByteArray) {
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == Frames.STREAM_IMAGE) {
            val (h, data) = Frames.parseAudio(bytes) ?: return
            emitAll(assembler.onStreamChunk(h.seq, data))
            if (assembler.inProgress) armStallWatchdog() else stallWatchdog?.cancel()
            return
        }
        _audioUp.tryEmit(bytes)
    }

    /**
     * Called once per connection when the glasses' capabilities first arrive: the app
     * pushes its settings (photo mode, volume, ...). Not on later CAPS replies, which
     * SET_PARAM itself triggers.
     */
    var onFirstCaps: (suspend (Frames.Caps) -> Unit)? = null
    @Volatile private var configured = false

    /** Waits for the ToF reading the glasses send after image [imageId]; MISSING on timeout. */
    suspend fun awaitTof(imageId: Int, timeoutMs: Long): TofReading {
        val d = tofWaiters.getOrPut(imageId) { kotlinx.coroutines.CompletableDeferred() }
        return (withTimeoutOrNull(timeoutMs) { d.await() } ?: TofReading.MISSING).also {
            tofWaiters.remove(imageId)
            if (tofWaiters.size > 32) tofWaiters.clear()  // never grows unbounded
        }
    }

    val isV2: Boolean get() = manager?.hasV2 == true

    /** Sends a COMMAND (protocol v2). False if not connected, v1 firmware, or the write failed. */
    suspend fun sendCommand(bytes: ByteArray): Boolean {
        val mgr = manager ?: return false
        if (!mgr.hasV2) return false
        return try {
            withTimeout(WRITE_TIMEOUT_MS) { mgr.writeCommand(bytes) }
            true
        } catch (e: Exception) {
            log("COMMAND 0x%02x failed: ${e.message ?: e::class.simpleName}".format(bytes.firstOrNull() ?: 0))
            false
        }
    }

    /** Writes one AUDIO_DOWN frame (write without response). */
    suspend fun writeAudioFrame(frame: ByteArray): Boolean {
        val mgr = manager ?: return false
        return try { mgr.writeAudioDown(frame); true } catch (e: Exception) { false }
    }

    suspend fun request2mPhy(enable: Boolean) { manager?.request2mPhy(enable) }

    /** Writes the composed sentence to the glasses. Returns false if not connected or the write failed. */
    suspend fun sendResultText(text: String): Boolean {
        val mgr = manager ?: return false.also { log("RESULT_TEXT not sent: glasses not connected") }
        return try {
            withTimeout(WRITE_TIMEOUT_MS) { mgr.writeResultText(text) }
            log("→ RESULT_TEXT sent (${text.toByteArray().size} bytes): \"$text\"")
            true
        } catch (e: Exception) {
            log("RESULT_TEXT write failed: ${e.message ?: e::class.simpleName}")
            false
        }
    }

    fun log(text: String) {
        Log.d(TAG, text)
        _log.update { (listOf(LogLine(timestamp(), text)) + it).take(200) }
    }

    private fun timestamp() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    companion object {
        const val TAG = "GlassesLink"
        private const val SCAN_WINDOW_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val CHUNK_STALL_TIMEOUT_MS = 4_000L
    }
}

package re.abbot.librecr.app.wear

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.connection.AutomaticHandoff
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> Task<T>.awaitHandoff(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

internal suspend fun handoffRpc(context: Context, nodeId: String, path: String, payload: JSONObject): JSONObject =
    withTimeout(AutomaticHandoff.RPC_TIMEOUT_MS) {
        JSONObject(String(Wearable.getMessageClient(context).sendRequest(
            nodeId, path, payload.toString().toByteArray(Charsets.UTF_8),
        ).awaitHandoff(), Charsets.UTF_8))
    }

/** A stalled Play services query must not retain the handoff coordinator's mutex indefinitely. */
internal suspend fun <T : Any> handoffQueryOrNull(
    operation: String,
    timeoutMs: Long = 5_000L,
    query: suspend () -> T,
): T? = try {
    withTimeoutOrNull(timeoutMs) { query() }.also { result ->
        if (result == null && BleLog.isEnabled()) BleLog.log("automatic handoff: query timeout operation=$operation timeoutMs=$timeoutMs")
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    if (BleLog.isEnabled()) BleLog.log("automatic handoff: query failed operation=$operation error=${failure.javaClass.simpleName}")
    null
}

internal suspend fun nearbyHandoffNode(context: Context, nodeId: String): Boolean =
    handoffQueryOrNull("connected_nodes") {
        Wearable.getNodeClient(context).connectedNodes.awaitHandoff().any { it.id == nodeId && it.isNearby }
    } ?: false

/** The process scope survives listener-service destruction during a BLE transfer. */
internal fun handoffTask(scope: CoroutineScope, block: suspend () -> JSONObject): Task<ByteArray> {
    val result = TaskCompletionSource<ByteArray>()
    scope.launch {
        try {
            result.trySetResult(block().toString().toByteArray(Charsets.UTF_8))
        } catch (failure: CancellationException) {
            result.trySetException(failure)
            throw failure
        } catch (failure: Exception) {
            BleLog.log("automatic handoff: RPC failed: ${failure.message}")
            result.trySetResult(handoffReply(false).toString().toByteArray(Charsets.UTF_8))
        }
    }
    return result.task
}

internal fun handoffReply(accepted: Boolean): JSONObject = JSONObject().put("accepted", accepted)

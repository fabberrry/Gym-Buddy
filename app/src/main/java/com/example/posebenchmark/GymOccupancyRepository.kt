package com.example.posebenchmark

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import android.util.Log

interface OccupancyClock {
    fun elapsedMs(): Long
    fun epochMs(): Long
}

object AndroidOccupancyClock : OccupancyClock {
    override fun elapsedMs() = SystemClock.elapsedRealtime()
    override fun epochMs() = System.currentTimeMillis()
}

sealed interface GymOccupancyUiState {
    data object Loading : GymOccupancyUiState
    data object Unavailable : GymOccupancyUiState
    data class Available(
        val room: GymRoomState,
        val stale: Boolean,
        val connectionUnavailable: Boolean,
        val ageSeconds: Long,
        val sessionChanged: Boolean
    ) : GymOccupancyUiState
}

/** Keeps the latest absolute snapshot. Events never alter the displayed count. */
class GymOccupancyRepository(
    private val api: GymOccupancyApi,
    private val expectedDeviceId: String,
    private val expectedRoomId: String,
    private val clock: OccupancyClock = AndroidOccupancyClock,
    private val staleAfterMs: Long = 5_000
) {
    private var latest: GymRoomState? = null
    private var lastFreshElapsedMs = -1L
    private var offline = false
    private var sessionChanged = false
    private val requestMutex = Mutex()

    suspend fun refresh(): GymOccupancyUiState = requestMutex.withLock {
        try {
            val incoming = api.getState()
            require(incoming.schemaVersion == 1 && incoming.deviceId == expectedDeviceId &&
                incoming.roomId == expectedRoomId && incoming.count in 0..4_294_967_295L &&
                incoming.sequence >= 0 && incoming.timestamp >= 0 &&
                incoming.status in setOf("valid", "uncertain")) { "Invalid room identity/state" }
            val previous = latest
            if (previous == null || incoming.sessionId != previous.sessionId ||
                incoming.sequence > previous.sequence) {
                if (previous != null && incoming.sessionId != previous.sessionId) sessionChanged = true
                latest = incoming
                lastFreshElapsedMs = clock.elapsedMs()
            }
            offline = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
             Log.e("GymOccupancy", "Failed to fetch gym occupancy", e)
            offline = true
        }
        current()
    }

    fun current(): GymOccupancyUiState {
        val room = latest ?: return GymOccupancyUiState.Unavailable
        val elapsedAge = if (lastFreshElapsedMs < 0) Long.MAX_VALUE else
            max(0, clock.elapsedMs() - lastFreshElapsedMs)
        val timestampAge = max(0, clock.epochMs() - room.timestamp)
        val age = max(elapsedAge, timestampAge)
        return GymOccupancyUiState.Available(room, offline || age > staleAfterMs,
            offline, age / 1000, sessionChanged)
    }
}

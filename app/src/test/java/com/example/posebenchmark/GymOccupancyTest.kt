package com.example.posebenchmark

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymOccupancyTest {
    private val baseJson = """{"schemaVersion":1,"deviceId":"counter-01","roomId":"room-01","sessionId":"s1","sequence":1,"count":0,"event":"none","timestamp":100000,"status":"valid","confidence":null}"""

    @Test fun parsesValidZeroAndPositiveAbsoluteCounts() {
        val zero = GymRoomStateParser.parse(baseJson)
        assertEquals(0L, zero.count)
        val positive = GymRoomStateParser.parse(baseJson.replace("\"count\":0", "\"count\":4294967295"))
        assertEquals(4_294_967_295L, positive.count)
    }

    @Test fun rejectsMalformedAndWrongContract() {
        for (payload in listOf("{", baseJson.replace("\"count\":0", "\"count\":-1"),
            baseJson.replace("\"status\":\"valid\"", "\"status\":\"ok\""),
            baseJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            baseJson.replace("\"roomId\":\"room-01\"", "\"roomId\":123"),
            baseJson.replace("\"count\":0", "\"count\":0.5"))) {
            var failed = false
            try { GymRoomStateParser.parse(payload) } catch (_: Exception) { failed = true }
            assertTrue("Should reject $payload", failed)
        }
    }

    private class FakeClock(var elapsed: Long = 0, var epoch: Long = 100_000) : OccupancyClock {
        override fun elapsedMs() = elapsed
        override fun epochMs() = epoch
    }
    private class FakeApi(var next: GymRoomState) : GymOccupancyApi {
        var fail = false
        override suspend fun getState(): GymRoomState {
            if (fail) throw IOException("server down")
            return next
        }
    }
    private fun state(count: Long = 0, sequence: Long = 1, session: String = "s1",
                      status: String = "valid", timestamp: Long = 100_000) =
        GymRoomState(1, "counter-01", "room-01", session, sequence, count,
            "none", timestamp, status, null)

    @Test fun outageKeepsLastCountAndZeroIsNotFailure() = runBlocking {
        val clock = FakeClock()
        val api = FakeApi(state())
        val repository = GymOccupancyRepository(api, "counter-01", "room-01", clock)
        assertEquals(0L, (repository.refresh() as GymOccupancyUiState.Available).room.count)
        api.next = state(count = 3, sequence = 2)
        assertEquals(3L, (repository.refresh() as GymOccupancyUiState.Available).room.count)
        api.fail = true
        val failed = repository.refresh() as GymOccupancyUiState.Available
        assertEquals(3L, failed.room.count)
        assertTrue(failed.stale)
        assertTrue(failed.connectionUnavailable)
        api.fail = false
        api.next = state(count = 4, sequence = 3)
        val recovered = repository.refresh() as GymOccupancyUiState.Available
        assertEquals(4L, recovered.room.count)
        assertFalse(recovered.connectionUnavailable)
    }

    @Test fun initialFailureIsUnavailableAndOldTimestampIsStale() = runBlocking {
        val clock = FakeClock(epoch = 110_000)
        val api = FakeApi(state(count = 7))
        api.fail = true
        val repository = GymOccupancyRepository(api, "counter-01", "room-01", clock)
        assertEquals(GymOccupancyUiState.Unavailable, repository.refresh())
        api.fail = false
        val loaded = repository.refresh() as GymOccupancyUiState.Available
        assertEquals(7L, loaded.room.count)
        assertTrue(loaded.stale)
        assertFalse(loaded.connectionUnavailable)
    }

    @Test fun staleUncertainAndRevisionRules() = runBlocking {
        val clock = FakeClock()
        val api = FakeApi(state(count = 12, sequence = 5, status = "uncertain"))
        val repository = GymOccupancyRepository(api, "counter-01", "room-01", clock)
        val first = repository.refresh() as GymOccupancyUiState.Available
        assertEquals("uncertain", first.room.status)
        assertFalse(first.stale)
        clock.elapsed = 5_100
        clock.epoch = 105_100
        assertTrue((repository.current() as GymOccupancyUiState.Available).stale)
        api.next = state(count = 99, sequence = 4, timestamp = 105_100)
        assertEquals(12L, (repository.refresh() as GymOccupancyUiState.Available).room.count)
        api.next = state(count = 8, sequence = 5, timestamp = 105_100)
        assertEquals(12L, (repository.refresh() as GymOccupancyUiState.Available).room.count)
        api.next = state(count = 9, sequence = 6, timestamp = 105_100)
        assertEquals(9L, (repository.refresh() as GymOccupancyUiState.Available).room.count)
        api.next = state(count = 2, sequence = 0, session = "s2", timestamp = 105_100)
        val restarted = repository.refresh() as GymOccupancyUiState.Available
        assertEquals(2L, restarted.room.count)
        assertTrue(restarted.sessionChanged)
    }

    @Test fun wrongIdentityKeepsLastState() = runBlocking {
        val clock = FakeClock()
        val api = FakeApi(state(count = 4))
        val repository = GymOccupancyRepository(api, "counter-01", "room-01", clock)
        repository.refresh()
        api.next = state(count = 0).copy(roomId = "wrong", sequence = 2)
        val retained = repository.refresh() as GymOccupancyUiState.Available
        assertEquals(4L, retained.room.count)
        assertTrue(retained.connectionUnavailable)
    }
}

package com.example.posebenchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class GymOccupancyViewModel : ViewModel() {
    private val repository = GymOccupancyRepository(
        HttpGymOccupancyApi(BuildConfig.OCCUPANCY_BASE_URL),
        BuildConfig.OCCUPANCY_DEVICE_ID,
        BuildConfig.OCCUPANCY_ROOM_ID
    )
    private val mutableState = MutableStateFlow<GymOccupancyUiState>(GymOccupancyUiState.Loading)
    val state: StateFlow<GymOccupancyUiState> = mutableState
    private var pollingJob: Job? = null
    private var refreshJob: Job? = null

    fun refreshNow() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.value = repository.refresh()
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            var retryMs = 1_000L
            while (isActive) {
                val updated = repository.refresh()
                mutableState.value = updated
                val failed = updated is GymOccupancyUiState.Unavailable ||
                    (updated is GymOccupancyUiState.Available && updated.connectionUnavailable)
                val waitMs = if (failed) retryMs + Random.nextLong(0, 250) else 3_000L
                retryMs = if (failed) (retryMs * 2).coerceAtMost(30_000) else 1_000L
                delay(waitMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}

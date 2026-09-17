package com.example.posebenchmark

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface GymOccupancyApi {
    suspend fun getState(): GymRoomState
}

/** Read-only HTTP adapter; all calls run off the UI thread. */
class HttpGymOccupancyApi(baseUrl: String) : GymOccupancyApi {
    private val endpoint = URL(baseUrl.trimEnd('/') + "/v1/state")

    override suspend fun getState(): GymRoomState = withContext(Dispatchers.IO) {
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Occupancy HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            GymRoomStateParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }
}

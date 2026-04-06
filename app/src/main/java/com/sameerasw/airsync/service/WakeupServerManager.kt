package com.sameerasw.airsync.service

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.utils.WakeupHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

object WakeupServerManager {
    private const val TAG = "WakeupServerManager"
    private const val HTTP_PORT = 8888
    private const val WAKEUP_ENDPOINT = "/wakeup"

    private var httpServerSocket: ServerSocket? = null
    private var serviceScope: CoroutineScope? = null
    private var isRunning = false

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return

        val appContext = context.applicationContext
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope ->
            scope.launch {
                try {
                    isRunning = true
                    startHttpServer(appContext, scope)
                    Log.i(TAG, "Wake-up HTTP listener started on port $HTTP_PORT")
                } catch (e: Exception) {
                    isRunning = false
                    Log.e(TAG, "Failed to start wake-up listener", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false

        try {
            httpServerSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HTTP server socket", e)
        }
        httpServerSocket = null

        serviceScope?.cancel()
        serviceScope = null

        Log.i(TAG, "Wake-up HTTP listener stopped")
    }

    private suspend fun startHttpServer(context: Context, scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            try {
                httpServerSocket = ServerSocket(HTTP_PORT)

                scope.launch {
                    while (isRunning && httpServerSocket?.isClosed == false) {
                        try {
                            val clientSocket = httpServerSocket?.accept()
                            if (clientSocket != null) {
                                launch {
                                    handleHttpRequest(context, clientSocket)
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                Log.e(TAG, "Error accepting HTTP connection", e)
                            }
                        }
                    }
                }

                Log.d(TAG, "HTTP server started on port $HTTP_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
                throw e
            }
        }
    }

    private suspend fun handleHttpRequest(context: Context, clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                clientSocket.use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = PrintWriter(socket.getOutputStream(), true)

                    val requestLine = input.readLine() ?: return@withContext
                    val parts = requestLine.split(" ")
                    if (parts.size < 3) return@withContext

                    val method = parts[0]
                    val path = parts[1]

                    var contentLength = 0
                    var line: String?
                    while (input.readLine().also { line = it } != null) {
                        val headerLine = line ?: break
                        if (headerLine.isEmpty()) break
                        if (headerLine.lowercase().startsWith("content-length:")) {
                            contentLength = headerLine.substring(15).trim().toIntOrNull() ?: 0
                        }
                    }

                    if (method == "POST" && path == WAKEUP_ENDPOINT) {
                        val body = if (contentLength > 0) {
                            val bodyChars = CharArray(contentLength)
                            input.read(bodyChars, 0, contentLength)
                            String(bodyChars)
                        } else {
                            ""
                        }

                        Log.d(TAG, "Received HTTP wake-up request: $body")

                        try {
                            val jsonRequest = JSONObject(body)

                            val macIp: String
                            val macPort: Int
                            val macName: String

                            if (jsonRequest.has("data")) {
                                val data = jsonRequest.getJSONObject("data")
                                macIp = data.optString("macIP", "")
                                macPort = data.optInt("macPort", 6996)
                                macName = data.optString("macName", "Mac")
                            } else {
                                macIp = jsonRequest.optString("macIp", "")
                                macPort = jsonRequest.optInt("macPort", 6996)
                                macName = jsonRequest.optString("macName", "Mac")
                            }

                            val response =
                                """{"status": "success", "message": "Wake-up request received"}"""
                            sendHttpResponse(output, 200, "OK", response)

                            WakeupHandler.processWakeupRequest(context, macIp, macPort, macName)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing wake-up request", e)
                            val response = """{"status": "error", "message": "Invalid JSON"}"""
                            sendHttpResponse(output, 400, "Bad Request", response)
                        }
                    } else if (method == "OPTIONS") {
                        sendCorsResponse(output)
                    } else {
                        val response =
                            """{"status": "error", "message": "Method not allowed or path not found"}"""
                        sendHttpResponse(output, 405, "Method Not Allowed", response)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling HTTP request", e)
            }
        }
    }

    private fun sendHttpResponse(
        output: PrintWriter,
        statusCode: Int,
        statusText: String,
        body: String
    ) {
        output.println("HTTP/1.1 $statusCode $statusText")
        output.println("Content-Type: application/json")
        output.println("Access-Control-Allow-Origin: *")
        output.println("Access-Control-Allow-Methods: POST, OPTIONS")
        output.println("Access-Control-Allow-Headers: Content-Type")
        output.println("Content-Length: ${body.length}")
        output.println()
        output.print(body)
        output.flush()
    }

    private fun sendCorsResponse(output: PrintWriter) {
        output.println("HTTP/1.1 200 OK")
        output.println("Access-Control-Allow-Origin: *")
        output.println("Access-Control-Allow-Methods: POST, OPTIONS")
        output.println("Access-Control-Allow-Headers: Content-Type")
        output.println("Content-Length: 0")
        output.println()
        output.flush()
    }
}

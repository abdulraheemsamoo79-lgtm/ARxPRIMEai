package com.example.gemini

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.bridge.AndroidActionBridge
import com.example.model.ActionResult
import com.example.model.ActionStatus
import com.example.model.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (AssistantState) -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onTextReceived: (String, Boolean) -> Unit, // text, isUser
    private val onActionExecuted: (ActionResult) -> Unit,
    private val onInterrupted: () -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        private const val WS_HOST = "generativelanguage.googleapis.com"
        private const val WS_PATH = "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isSetupComplete = false
    private var reconnectJob: Job? = null
    private var shouldReconnect = true

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        shouldReconnect = true
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured.")
            onTextReceived("Please configure your GEMINI_API_KEY in the Secrets panel.", false)
            return
        }

        if (isConnected) return

        val url = "wss://$WS_HOST$WS_PATH?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully.")
                isConnected = true
                sendInitialSetup()
                scope.launch(Dispatchers.Main) {
                    onStateChanged(AssistantState.IDLE)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                isConnected = false
                isSetupComplete = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                isSetupComplete = false
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.localizedMessage}", t)
                isConnected = false
                isSetupComplete = false
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            if (shouldReconnect && !isConnected) {
                Log.d(TAG, "Attempting WebSocket reconnection...")
                connect()
            }
        }
    }

    private fun sendInitialSetup() {
        try {
            val setupJson = JSONObject().apply {
                val setupObj = JSONObject()
                setupObj.put("model", LIVE_MODEL)

                // Generation Config with adult male voice (Puck)
                val generationConfig = JSONObject()
                generationConfig.put("responseModalities", JSONArray().put("AUDIO"))
                
                val speechConfig = JSONObject()
                val voiceConfig = JSONObject()
                val prebuiltVoiceConfig = JSONObject()
                prebuiltVoiceConfig.put("voiceName", "Puck") // Natural Adult Male Voice
                voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
                speechConfig.put("voiceConfig", voiceConfig)
                generationConfig.put("speechConfig", speechConfig)

                setupObj.put("generationConfig", generationConfig)

                // System Instruction with deep multilingual capabilities and action guidelines
                val systemInstruction = JSONObject()
                val parts = JSONArray()
                val sysPart = JSONObject()
                sysPart.put(
                    "text",
                    """
                    You are AR PRIME AI, a powerful, helpful, and natural conversational AI voice assistant with a deep, natural adult male voice.
                    
                    MULTILINGUAL VOICE INTELLIGENCE:
                    - You understand and speak fluently in Hindi, English, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, and other languages.
                    - Automatically detect the user's spoken language in real-time.
                    - If the user speaks Hindi, respond in natural, fluent Hindi.
                    - If the user speaks English, respond in natural English.
                    - If the user speaks Hinglish (mix of Hindi & English), respond naturally in authentic Hinglish.
                    - If the user switches languages mid-conversation, seamlessly switch your language immediately to match without hesitation.
                    - Do NOT require the user to manually select a language.
                    
                    APP CONTROL & REAL ANDROID FUNCTION CALLING:
                    You have real native tool-calling capabilities to execute device actions:
                    1. openWhatsApp(): Trigger this when the user asks to open WhatsApp in any phrasing or language (e.g. "Open WhatsApp", "WhatsApp open karo", "WhatsApp kholo", "WhatsApp chalao", "Open my WhatsApp").
                    2. openApp(appName): Trigger this when the user asks to open an app or settings (e.g. "Open YouTube", "Open Chrome", "Open settings", "Camera kholo", "Open Spotify", "Open Maps").
                    3. openUrl(url): Trigger this when the user asks to open a web page or link.
                    4. makeCall(phoneNumber): Trigger this when the user gives a phone number to call (e.g. "Call 9876543210").
                    5. callContact(contactName): Trigger this when the user asks to call someone by name (e.g. "Call Mom", "Mummy ko call karo", "Call Rahul", "Dad ko phone lagao", "Rahul ko call karo").
                    
                    CRITICAL EXECUTION RULES:
                    - NEVER just verbally claim "I am opening WhatsApp" or "Calling Mom" without executing the tool call! ALWAYS emit the tool call so the Android app performs the native action.
                    - When a tool result is returned, acknowledge it naturally with your adult male voice.
                    - If callContact returns multiple matches (e.g. multiple Rahuls), ask the user for clarification with the matching names.
                    - Keep your voice responses concise, conversational, and direct.
                    """.trimIndent()
                )
                parts.put(sysPart)
                systemInstruction.put("parts", parts)
                setupObj.put("systemInstruction", systemInstruction)

                // Tools Declarations
                val toolsArray = JSONArray()
                val toolObj = JSONObject()
                val funcDecls = JSONArray()

                // Tool 1: openWhatsApp
                funcDecls.put(JSONObject().apply {
                    put("name", "openWhatsApp")
                    put("description", "Opens WhatsApp application on the device. Call this whenever the user asks to open, launch, or use WhatsApp.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject())
                    })
                })

                // Tool 2: openApp
                funcDecls.put(JSONObject().apply {
                    put("name", "openApp")
                    put("description", "Opens an installed Android application or device settings (e.g. YouTube, Chrome, Settings, Camera, Maps, Spotify, Calculator).")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("appName", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The name of the app or setting to open, e.g. YouTube, Chrome, Settings, Camera, Spotify")
                            })
                        })
                        put("required", JSONArray().put("appName"))
                    })
                })

                // Tool 3: openUrl
                funcDecls.put(JSONObject().apply {
                    put("name", "openUrl")
                    put("description", "Opens a web URL in the device browser.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("url", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The URL to open, e.g. https://google.com")
                            })
                        })
                        put("required", JSONArray().put("url"))
                    })
                })

                // Tool 4: makeCall
                funcDecls.put(JSONObject().apply {
                    put("name", "makeCall")
                    put("description", "Initiates a phone call or opens the dialer for a given phone number.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("phoneNumber", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The phone number to dial, e.g. 9876543210")
                            })
                        })
                        put("required", JSONArray().put("phoneNumber"))
                    })
                })

                // Tool 5: callContact
                funcDecls.put(JSONObject().apply {
                    put("name", "callContact")
                    put("description", "Searches the device contacts by name (e.g. Mom, Mummy, Rahul, Dad) and initiates a call.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("contactName", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The name of the contact to call, e.g. Mom, Mummy, Rahul, Dad")
                            })
                        })
                        put("required", JSONArray().put("contactName"))
                    })
                })

                toolObj.put("functionDeclarations", funcDecls)
                toolsArray.put(toolObj)
                setupObj.put("tools", toolsArray)

                put("setup", setupObj)
            }

            webSocket?.send(setupJson.toString())
            isSetupComplete = true
            Log.d(TAG, "Setup message sent to Gemini Live.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcmData: ByteArray) {
        if (!isConnected || webSocket == null) return
        try {
            val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val msg = JSONObject().apply {
                val realtimeInput = JSONObject()
                val mediaChunks = JSONArray()
                val chunk = JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Data)
                }
                mediaChunks.put(chunk)
                realtimeInput.put("mediaChunks", mediaChunks)
                put("realtimeInput", realtimeInput)
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    fun sendTextMessage(userText: String) {
        if (!isConnected || webSocket == null) {
            connect()
        }
        scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (!isConnected && attempts < 10) {
                delay(300)
                attempts++
            }
            try {
                val msg = JSONObject().apply {
                    val clientContent = JSONObject()
                    val turns = JSONArray()
                    val turn = JSONObject()
                    turn.put("role", "user")
                    val parts = JSONArray()
                    parts.put(JSONObject().put("text", userText))
                    turn.put("parts", parts)
                    turns.put(turn)
                    clientContent.put("turns", turns)
                    clientContent.put("turnComplete", true)
                    put("clientContent", clientContent)
                }
                webSocket?.send(msg.toString())
                scope.launch(Dispatchers.Main) {
                    onTextReceived(userText, true)
                    onStateChanged(AssistantState.THINKING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending text message: ${e.message}", e)
            }
        }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            // 1. Check for serverContent
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Check interruption
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "User interrupted AI speech.")
                    scope.launch(Dispatchers.Main) {
                        onInterrupted()
                        onStateChanged(AssistantState.LISTENING)
                    }
                    return
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Audio part
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Audio = inlineData.optString("data", "")
                                if (base64Audio.isNotEmpty()) {
                                    val pcmBytes = Base64.decode(base64Audio, Base64.NO_WRAP)
                                    onAudioReceived(pcmBytes)
                                    scope.launch(Dispatchers.Main) {
                                        onStateChanged(AssistantState.SPEAKING)
                                    }
                                }
                            }

                            // Text part
                            if (part.has("text")) {
                                val text = part.optString("text", "")
                                if (text.isNotBlank()) {
                                    scope.launch(Dispatchers.Main) {
                                        onTextReceived(text, false)
                                    }
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    scope.launch(Dispatchers.Main) {
                        delay(200)
                        onStateChanged(AssistantState.IDLE)
                    }
                }
            }

            // 2. Check for toolCall
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val callId = call.optString("id", "call_${System.currentTimeMillis()}")
                        val name = call.optString("name", "")
                        val args = call.optJSONObject("args") ?: JSONObject()

                        executeFunctionCall(callId, name, args)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message: ${e.message}", e)
        }
    }

    private fun executeFunctionCall(callId: String, name: String, args: JSONObject) {
        scope.launch(Dispatchers.Main) {
            onStateChanged(AssistantState.EXECUTING_ACTION)
            val result = when (name) {
                "openWhatsApp" -> {
                    AndroidActionBridge.openWhatsApp(context)
                }
                "openApp" -> {
                    val appName = args.optString("appName", "")
                    AndroidActionBridge.openApp(context, appName)
                }
                "openUrl" -> {
                    val url = args.optString("url", "")
                    AndroidActionBridge.openUrl(context, url)
                }
                "makeCall" -> {
                    val phoneNumber = args.optString("phoneNumber", "")
                    AndroidActionBridge.makeCall(context, phoneNumber)
                }
                "callContact" -> {
                    val contactName = args.optString("contactName", "")
                    AndroidActionBridge.callContact(context, contactName)
                }
                else -> {
                    ActionResult(
                        success = false,
                        message = "Unknown tool action: $name",
                        actionName = name,
                        status = ActionStatus.ERROR
                    )
                }
            }

            onActionExecuted(result)

            // Send tool response back to Gemini Live WebSocket
            scope.launch(Dispatchers.IO) {
                sendToolResponse(callId, result)
            }
        }
    }

    private fun sendToolResponse(callId: String, result: ActionResult) {
        try {
            val responseMsg = JSONObject().apply {
                val toolResponse = JSONObject()
                val functionResponses = JSONArray()
                val fnResp = JSONObject()
                fnResp.put("id", callId)

                val responseObj = JSONObject()
                val outputObj = JSONObject()
                outputObj.put("success", result.success)
                outputObj.put("message", result.message)
                outputObj.put("target", result.target)
                outputObj.put("status", result.status.name)
                if (result.contactsList.isNotEmpty()) {
                    val contactsArr = JSONArray()
                    for (c in result.contactsList) {
                        contactsArr.put(JSONObject().apply {
                            put("name", c.name)
                            put("phoneNumber", c.phoneNumber)
                            put("type", c.type)
                        })
                    }
                    outputObj.put("contacts", contactsArr)
                }
                responseObj.put("output", outputObj)
                fnResp.put("response", responseObj)

                functionResponses.put(fnResp)
                toolResponse.put("functionResponses", functionResponses)
                put("toolResponse", toolResponse)
            }

            webSocket?.send(responseMsg.toString())
            Log.d(TAG, "Sent tool response for callId $callId: ${result.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tool response: ${e.message}", e)
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        isSetupComplete = false
    }
}

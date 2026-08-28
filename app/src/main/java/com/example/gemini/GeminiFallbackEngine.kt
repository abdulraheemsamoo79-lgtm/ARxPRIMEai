package com.example.gemini

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.bridge.AndroidActionBridge
import com.example.model.ActionResult
import com.example.model.ActionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiFallbackEngine(private val context: Context) {
    companion object {
        private const val TAG = "GeminiFallbackEngine"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun processUserText(
        userText: String,
        onActionExecuted: (ActionResult) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Please configure your GEMINI_API_KEY in the Secrets panel."
        }

        try {
            val url = "$BASE_URL?key=$apiKey"
            val requestJson = JSONObject().apply {
                val contents = JSONArray()
                val content = JSONObject()
                content.put("role", "user")
                val parts = JSONArray()
                parts.put(JSONObject().put("text", userText))
                content.put("parts", parts)
                contents.put(content)
                put("contents", contents)

                // System Instruction
                val systemInstruction = JSONObject()
                val sysParts = JSONArray()
                sysParts.put(JSONObject().put("text", """
                    You are AR PRIME AI, an intelligent, natural conversational AI voice assistant.
                    Respond in the same language as the user (Hindi, English, Hinglish, etc.).
                    Use the provided tools when an action is requested:
                    - openWhatsApp() for opening WhatsApp
                    - openApp(appName) for opening apps or settings (e.g. YouTube, Chrome, Settings)
                    - openUrl(url) for opening links
                    - makeCall(phoneNumber) for calling a number
                    - callContact(contactName) for calling a contact
                """.trimIndent()))
                systemInstruction.put("parts", sysParts)
                put("systemInstruction", systemInstruction)

                // Tools
                val toolsArray = JSONArray()
                val toolObj = JSONObject()
                val funcDecls = JSONArray()

                funcDecls.put(JSONObject().apply {
                    put("name", "openWhatsApp")
                    put("description", "Opens WhatsApp application.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject())
                    })
                })

                funcDecls.put(JSONObject().apply {
                    put("name", "openApp")
                    put("description", "Opens an installed Android app or settings.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("appName", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The name of the app to open")
                            })
                        })
                        put("required", JSONArray().put("appName"))
                    })
                })

                funcDecls.put(JSONObject().apply {
                    put("name", "openUrl")
                    put("description", "Opens a web URL.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("url", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The URL to open")
                            })
                        })
                        put("required", JSONArray().put("url"))
                    })
                })

                funcDecls.put(JSONObject().apply {
                    put("name", "makeCall")
                    put("description", "Calls a phone number.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("phoneNumber", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "Phone number to call")
                            })
                        })
                        put("required", JSONArray().put("phoneNumber"))
                    })
                })

                funcDecls.put(JSONObject().apply {
                    put("name", "callContact")
                    put("description", "Searches contacts and calls the contact.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("contactName", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The contact name to call")
                            })
                        })
                        put("required", JSONArray().put("contactName"))
                    })
                })

                toolObj.put("functionDeclarations", funcDecls)
                toolsArray.put(toolObj)
                put("tools", toolsArray)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)
            val request = Request.Builder().url(url).post(requestBody).build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext "Error connecting to AI service: ${response.code}"
            }

            val respJson = JSONObject(respBody)
            val candidates = respJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val contentObj = firstCandidate.optJSONObject("content")
                val parts = contentObj?.optJSONArray("parts")

                var replyText = ""
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("text")) {
                            replyText += part.getString("text")
                        }
                        if (part.has("functionCall")) {
                            val fnCall = part.getJSONObject("functionCall")
                            val fnName = fnCall.optString("name", "")
                            val fnArgs = fnCall.optJSONObject("args") ?: JSONObject()

                            val result = when (fnName) {
                                "openWhatsApp" -> AndroidActionBridge.openWhatsApp(context)
                                "openApp" -> AndroidActionBridge.openApp(context, fnArgs.optString("appName", ""))
                                "openUrl" -> AndroidActionBridge.openUrl(context, fnArgs.optString("url", ""))
                                "makeCall" -> AndroidActionBridge.makeCall(context, fnArgs.optString("phoneNumber", ""))
                                "callContact" -> AndroidActionBridge.callContact(context, fnArgs.optString("contactName", ""))
                                else -> ActionResult(false, "Unknown tool: $fnName", fnName, status = ActionStatus.ERROR)
                            }
                            onActionExecuted(result)
                            if (replyText.isBlank()) {
                                replyText = result.message
                            }
                        }
                    }
                }
                return@withContext if (replyText.isNotBlank()) replyText else "Action executed."
            }

            return@withContext "No response received."
        } catch (e: Exception) {
            Log.e(TAG, "Error in processUserText: ${e.message}", e)
            return@withContext "Error: ${e.localizedMessage}"
        }
    }
}

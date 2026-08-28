package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioStreamManager
import com.example.bridge.AndroidActionBridge
import com.example.gemini.GeminiFallbackEngine
import com.example.gemini.GeminiLiveClient
import com.example.model.ActionDetail
import com.example.model.ActionResult
import com.example.model.ActionStatus
import com.example.model.AssistantState
import com.example.model.ChatMessage
import com.example.model.ContactMatch
import com.example.model.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClarificationState(
    val isOpen: Boolean = false,
    val contactName: String = "",
    val matches: List<ContactMatch> = emptyList()
)

data class AssistantUiState(
    val state: AssistantState = AssistantState.IDLE,
    val isMicActive: Boolean = false,
    val isConnected: Boolean = false,
    val inputAmplitude: Float = 0f,
    val outputAmplitude: Float = 0f,
    val messages: List<ChatMessage> = emptyList(),
    val clarificationState: ClarificationState = ClarificationState(),
    val activeLanguage: String = "Auto-Detect (Hindi / English / Hinglish)"
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        AssistantUiState(
            messages = listOf(
                ChatMessage(
                    sender = MessageSender.ASSISTANT,
                    text = "Hello! I am AR PRIME AI, your voice assistant. You can speak to me in English, Hindi (हिंदी), Hinglish, Marathi, or any language. Say 'Open WhatsApp', 'Mummy ko call karo', 'Open YouTube', or ask me anything!"
                )
            )
        )
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val audioManager = AudioStreamManager(application.applicationContext, viewModelScope)
    private val fallbackEngine = GeminiFallbackEngine(application.applicationContext)

    private val liveClient = GeminiLiveClient(
        context = application.applicationContext,
        scope = viewModelScope,
        onStateChanged = { newState ->
            _uiState.update { it.copy(state = newState) }
        },
        onAudioReceived = { pcmChunk ->
            audioManager.enqueueAudioData(pcmChunk)
        },
        onTextReceived = { text, isUser ->
            addMessage(
                sender = if (isUser) MessageSender.USER else MessageSender.ASSISTANT,
                text = text
            )
        },
        onActionExecuted = { actionResult ->
            handleActionResult(actionResult)
        },
        onInterrupted = {
            audioManager.interruptPlayback()
            _uiState.update { it.copy(state = AssistantState.IDLE, outputAmplitude = 0f) }
        }
    )

    init {
        audioManager.onAudioChunk = { pcmChunk ->
            liveClient.sendAudioChunk(pcmChunk)
        }

        audioManager.onInputAmplitude = { amp ->
            _uiState.update { it.copy(inputAmplitude = amp) }
        }

        audioManager.onOutputAmplitude = { amp ->
            _uiState.update { it.copy(outputAmplitude = amp) }
        }

        connectLiveSession()
    }

    fun connectLiveSession() {
        liveClient.connect()
        _uiState.update { it.copy(isConnected = true) }
    }

    fun toggleMic() {
        if (_uiState.value.isMicActive) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        // If assistant is currently speaking, interrupt it first
        interrupt()

        val started = audioManager.startRecording()
        if (started) {
            _uiState.update {
                it.copy(
                    isMicActive = true,
                    state = AssistantState.LISTENING
                )
            }
        }
    }

    fun stopListening() {
        audioManager.stopRecording()
        _uiState.update {
            it.copy(
                isMicActive = false,
                inputAmplitude = 0f,
                state = if (it.state == AssistantState.LISTENING) AssistantState.THINKING else it.state
            )
        }
    }

    fun interrupt() {
        audioManager.interruptPlayback()
        _uiState.update {
            it.copy(
                state = if (it.isMicActive) AssistantState.LISTENING else AssistantState.IDLE,
                outputAmplitude = 0f
            )
        }
    }

    fun sendTextMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        interrupt()
        liveClient.sendTextMessage(clean)
    }

    fun executeQuickCommand(command: String) {
        val clean = command.trim()
        if (clean.isBlank()) return

        interrupt()
        addMessage(MessageSender.USER, clean)
        _uiState.update { it.copy(state = AssistantState.THINKING) }

        // Send through Gemini Live pipeline
        liveClient.sendTextMessage(clean)
    }

    private fun handleActionResult(result: ActionResult) {
        val actionDetail = ActionDetail(
            actionName = result.actionName,
            target = result.target,
            summary = result.message,
            status = result.status,
            isNativeAndroid = true,
            contactsList = result.contactsList
        )

        addMessage(
            sender = MessageSender.ACTION,
            text = result.message,
            actionDetail = actionDetail
        )

        if (result.status == ActionStatus.NEED_CLARIFICATION && result.contactsList.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    clarificationState = ClarificationState(
                        isOpen = true,
                        contactName = result.target,
                        matches = result.contactsList
                    )
                )
            }
        }
    }

    fun selectContactClarification(contact: ContactMatch) {
        _uiState.update { it.copy(clarificationState = ClarificationState(isOpen = false)) }
        viewModelScope.launch(Dispatchers.Main) {
            val callRes = AndroidActionBridge.makeCall(getApplication(), contact.phoneNumber)
            handleActionResult(
                ActionResult(
                    success = callRes.success,
                    message = "Calling ${contact.name} (${contact.phoneNumber})...",
                    actionName = "callContact",
                    target = "${contact.name} (${contact.phoneNumber})",
                    status = if (callRes.success) ActionStatus.SUCCESS else ActionStatus.ERROR,
                    contactsList = listOf(contact)
                )
            )
            // Inform Gemini Live about user's explicit selection
            liveClient.sendTextMessage("I chose ${contact.name}")
        }
    }

    fun dismissClarificationDialog() {
        _uiState.update { it.copy(clarificationState = ClarificationState(isOpen = false)) }
    }

    private fun addMessage(sender: MessageSender, text: String, actionDetail: ActionDetail? = null) {
        val newMsg = ChatMessage(
            sender = sender,
            text = text,
            actionDetail = actionDetail
        )
        _uiState.update {
            it.copy(messages = it.messages + newMsg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioManager.release()
        liveClient.disconnect()
    }
}

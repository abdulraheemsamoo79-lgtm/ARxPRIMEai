package com.example.model

import java.util.UUID

enum class AssistantState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    EXECUTING_ACTION
}

enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM,
    ACTION
}

enum class ActionStatus {
    SUCCESS,
    NEED_CLARIFICATION,
    ERROR,
    PENDING
}

data class ActionDetail(
    val actionName: String,
    val target: String,
    val summary: String,
    val status: ActionStatus,
    val isNativeAndroid: Boolean = true,
    val contactsList: List<ContactMatch> = emptyList()
)

data class ContactMatch(
    val id: String = "",
    val name: String,
    val phoneNumber: String,
    val type: String = "Mobile"
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val actionDetail: ActionDetail? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ActionResult(
    val success: Boolean,
    val message: String,
    val actionName: String,
    val target: String = "",
    val status: ActionStatus = if (success) ActionStatus.SUCCESS else ActionStatus.ERROR,
    val contactsList: List<ContactMatch> = emptyList()
)

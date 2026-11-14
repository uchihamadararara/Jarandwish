package com.jarvis.assistant.automation.agent

/**
 * Lightweight deterministic routing hints for the planner. This does not grant capabilities;
 * the executor must still check that the relevant Android permission, API, or integration exists.
 */
object TaskCapabilityRouter {
    enum class Kind {
        DEVICE_CONTROL,
        APP_WORKFLOW,
        COMMUNICATION,
        WEB_RESEARCH,
        CONTENT_CREATION,
        FILE_OPERATION,
        GENERAL_UI
    }

    data class Route(
        val kind: Kind,
        val preferredMethod: String,
        val requiresFreshObservation: Boolean = true,
        val mayRequireUserConfirmation: Boolean = false
    )

    fun resolve(goal: String): Route {
        val text = goal.lowercase().trim()
        return when {
            containsAny(text, "brightness", "volume", "flashlight", "torch", "wifi", "bluetooth", "ringer", "silent mode") ->
                Route(Kind.DEVICE_CONTROL, "Android system API or supported system intent", mayRequireUserConfirmation = true)

            containsAny(text, "whatsapp", "message", "send a text", "call", "phone call", "sms", "text him", "text her") ->
                Route(Kind.COMMUNICATION, "official app/API integration when available; otherwise Accessibility UI", mayRequireUserConfirmation = true)

            containsAny(text, "search the web", "research", "find information", "look up", "google", "website", "web page", "chrome") ->
                Route(Kind.WEB_RESEARCH, "browser navigation or a trusted search/API integration")

            containsAny(text, "create a file", "save file", "read file", "edit file", "rename file", "document", "spreadsheet", "folder") ->
                Route(Kind.FILE_OPERATION, "scoped Android file APIs or an explicitly connected file integration", mayRequireUserConfirmation = true)

            containsAny(text, "write a song", "make a video", "generate image", "create image", "edit video", "make music", "create content") ->
                Route(Kind.CONTENT_CREATION, "dedicated generation API or connected creative tool", mayRequireUserConfirmation = true)

            containsAny(text, "instagram", "spotify", "youtube", "telegram", "gmail", "maps", "open app", "play song", "like", "unlike") ->
                Route(Kind.APP_WORKFLOW, "app-specific integration first, then Android Accessibility UI")

            else -> Route(Kind.GENERAL_UI, "inspect available tools and integrations; use Accessibility only for visible, supported UI actions", mayRequireUserConfirmation = true)
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any(text::contains)
}

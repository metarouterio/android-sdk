package com.metarouter.analytics.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supported event types in the MetaRouter SDK.
 *
 * Based on the Segment spec for analytics events.
 */
@Serializable
enum class EventType {
    @SerialName("track")
    TRACK,

    @SerialName("identify")
    IDENTIFY,

    @SerialName("group")
    GROUP,

    @SerialName("screen")
    SCREEN,

    @SerialName("page")
    PAGE,

    @SerialName("alias")
    ALIAS;

    override fun toString(): String = when (this) {
        TRACK -> "track"
        IDENTIFY -> "identify"
        GROUP -> "group"
        SCREEN -> "screen"
        PAGE -> "page"
        ALIAS -> "alias"
    }
}

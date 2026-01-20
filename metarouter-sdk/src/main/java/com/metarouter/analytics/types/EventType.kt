package com.metarouter.analytics.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supported event types in the MetaRouter SDK.
 *
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

    override fun toString(): String = name.lowercase()
}

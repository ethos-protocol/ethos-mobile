package com.ethosprotocol.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Latest and minimum supported app versions, used by AppVersionChecker (#725). */
@Serializable
data class AppVersionInfo(
    @SerialName("latest_version") val latestVersion: String,
    @SerialName("minimum_version") val minimumVersion: String,
)

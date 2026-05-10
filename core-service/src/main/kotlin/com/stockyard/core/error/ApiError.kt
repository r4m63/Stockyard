package com.stockyard.core.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiErrorBody(val error: ApiError)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
)

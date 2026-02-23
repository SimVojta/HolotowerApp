package com.holotower.app.data.model

data class GlobalEntryStatus(
    val statusText: String,
    val postCount: Int?,
    val threshold: Int?,
    val currentIp: String?,
    val csrfToken: String?,
    val issuedToken: String?,
    val successMessages: List<String>,
    val errorMessages: List<String>
)

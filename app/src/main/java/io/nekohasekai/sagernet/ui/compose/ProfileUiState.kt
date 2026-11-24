package io.nekohasekai.sagernet.ui.compose

data class ProfileUiState(
    val id: Long,
    val name: String,
    val protocol: String,
    val address: String,
    val traffic: String,
    val status: String,
    val statusCode: Int,
    val errorDetail: String?,
    val selected: Boolean,
    val editEnabled: Boolean,
    val canShare: Boolean
)

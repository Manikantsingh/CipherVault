package com.example.ciphervault.core

import kotlinx.serialization.Serializable

@Serializable
data class CredentialEntry(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val website: String = "",
    val notes: String = "",
)

@Serializable
enum class OperationKind { UPSERT, DELETE }

@Serializable
data class VaultOperation(
    val operationId: String,
    val credentialId: String,
    val kind: OperationKind,
    val entry: CredentialEntry? = null,
    val logicalClock: Long,
    val deviceId: String,
) {
    init {
        require((kind == OperationKind.UPSERT) == (entry != null))
        require(entry == null || entry.id == credentialId)
    }
}

data class MergedVault(
    val credentials: List<CredentialEntry>,
    val maxLogicalClock: Long,
)
package com.example.ciphervault.core

object MergeEngine {
    fun merge(operations: Collection<VaultOperation>): MergedVault {
        val uniqueOperations = operations.distinctBy(VaultOperation::operationId)
        val winners = uniqueOperations.groupBy(VaultOperation::credentialId).mapValues { (_, candidates) ->
            candidates.maxWith(
                compareBy<VaultOperation> { it.logicalClock }
                    .thenBy { it.deviceId }
                    .thenBy { it.operationId },
            )
        }
        return MergedVault(
            credentials = winners.values
                .filter { it.kind == OperationKind.UPSERT }
                .mapNotNull(VaultOperation::entry)
                .sortedBy { it.title.lowercase() },
            maxLogicalClock = uniqueOperations.maxOfOrNull(VaultOperation::logicalClock) ?: 0,
        )
    }
}
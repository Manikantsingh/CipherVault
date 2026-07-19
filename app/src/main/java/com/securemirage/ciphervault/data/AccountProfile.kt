package com.securemirage.ciphervault.data

import java.security.MessageDigest

fun accountProfileId(driveAccountId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("drive:$driveAccountId".toByteArray())
        .joinToString("") { "%02x".format(it) }
package com.webunime.tv.data.api

open class ApiException(
    val httpCode: Int,
    override val message: String,
) : RuntimeException(message) {
    val isUnauthorized: Boolean get() = httpCode == 401
}

class UnauthorizedException : ApiException(401, "Sesi berakhir. Masuk lagi.")

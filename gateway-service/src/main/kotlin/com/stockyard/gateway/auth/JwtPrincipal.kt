package com.stockyard.gateway.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * Извлекает `userId` (sub claim) из валидного JWT. Должно вызываться внутри
 * `authenticate("auth-jwt") { … }` блока, иначе [principal] == null и метод бросает
 * [IllegalStateException] (мапится в 500). Под обычным flow Ktor jwt-плагин
 * сам отдаёт 401 для отсутствующего/невалидного токена ДО входа в handler.
 */
fun ApplicationCall.userId(): String {
    val principal = principal<JWTPrincipal>()
        ?: error("JWT principal missing — route is not protected by authenticate(\"auth-jwt\")")
    return principal.payload.subject
        ?: error("JWT principal has no subject claim")
}

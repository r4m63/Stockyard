package com.stockyard.core.auth

import de.mkammerer.argon2.Argon2Factory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id-хэширование с pepper'ом по ADR-006.
 *
 * Pepper применяется через HMAC-SHA256(password, pepper). Это позволяет
 * хранить pepper отдельно от хэшей в БД (если БД утечёт, pepper остаётся
 * у сервиса, и брутфорс сильно усложняется).
 *
 * Параметры Argon2id: m=19 MiB, t=2, p=1 — рекомендация OWASP для серверов
 * среднего класса. См. ADR-006.
 *
 * В TASK-004 класс готов к использованию, но никем ещё не вызывается.
 * Реальный auth-flow — TASK-005.
 */
class PasswordHasher(private val pepperBytes: ByteArray) {

    private val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(password: CharArray): String {
        val peppered = pepperedBytes(password)
        try {
            return argon.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, peppered)
        } finally {
            peppered.fill(0)
        }
    }

    fun verify(hash: String, password: CharArray): Boolean {
        val peppered = pepperedBytes(password)
        try {
            return argon.verify(hash, peppered)
        } finally {
            peppered.fill(0)
        }
    }

    /**
     * Возвращает HMAC-SHA256(password_bytes, pepper) как byte[].
     * argon2-jvm принимает byte[] напрямую (overload), что позволяет нам
     * не зашивать pepper в самом пароле и не вытаскивать его в логи.
     */
    private fun pepperedBytes(password: CharArray): ByteArray {
        val passwordBytes = password.joinToString("").toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance(HMAC_ALG)
            mac.init(SecretKeySpec(pepperBytes, HMAC_ALG))
            return mac.doFinal(passwordBytes)
        } finally {
            passwordBytes.fill(0)
        }
    }

    companion object {
        private const val ITERATIONS = 2
        private const val MEMORY_KIB = 19 * 1024  // 19 MiB
        private const val PARALLELISM = 1
        private const val HMAC_ALG = "HmacSHA256"
        const val PEPPER_MIN_BYTES = 32
    }
}

package com.stockyard.core.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class PasswordHasherTest {

    private val pepper = "this-is-a-test-pepper-32-bytes-min-length".toByteArray(Charsets.UTF_8)
    private val hasher = PasswordHasher(pepper)

    @Test
    fun `hash produces argon2id-prefixed string`() {
        val hash = hasher.hash("strong-password-123".toCharArray())
        hash shouldStartWith "\$argon2id\$"
    }

    @Test
    fun `verify accepts correct password`() {
        val pwd = "strong-password-123".toCharArray()
        val hash = hasher.hash(pwd.copyOf())   // copy because hash() may zero the array
        hasher.verify(hash, pwd) shouldBe true
    }

    @Test
    fun `verify rejects wrong password`() {
        val hash = hasher.hash("correct-password".toCharArray())
        hasher.verify(hash, "wrong-password".toCharArray()) shouldBe false
    }

    @Test
    fun `different peppers produce non-verifiable hashes`() {
        val pwd = "same-password".toCharArray()
        val hashA = hasher.hash(pwd.copyOf())

        val differentPepper = "different-pepper-32-bytes-min-length----".toByteArray(Charsets.UTF_8)
        val hasherB = PasswordHasher(differentPepper)

        // Хэш от hasherA не должен проходить verify через hasherB —
        // pepper меняет вход argon2.
        hasherB.verify(hashA, pwd) shouldBe false
    }

    @Test
    fun `same input produces different hashes due to random salt`() {
        val pwd = "same-password".toCharArray()
        val hash1 = hasher.hash(pwd.copyOf())
        val hash2 = hasher.hash(pwd.copyOf())

        // Salt random — хэши различаются, но оба верифицируются.
        (hash1 != hash2) shouldBe true
        hasher.verify(hash1, pwd.copyOf()) shouldBe true
        hasher.verify(hash2, pwd.copyOf()) shouldBe true
    }

    @Test
    fun `unicode passwords supported`() {
        val pwd = "пароль-с-кириллицей-🔒".toCharArray()
        val hash = hasher.hash(pwd.copyOf())
        hasher.verify(hash, pwd.copyOf()) shouldBe true
    }
}

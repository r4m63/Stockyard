package com.stockyard.core.domain.user

import com.stockyard.core.auth.PasswordHasher
import com.stockyard.core.persistence.TransactionManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit-тесты на validation в [UserService.register]. Валидация бросает [ValidationException]
 * ДО входа в `tx.withTx` и `hasher.hash`, поэтому моки безопасны без сетапа — они не
 * вызываются для невалидных входов.
 */
class UserServiceUnitTest {

    private val repo = mockk<UserRepository>()
    private val tx = mockk<TransactionManager>()
    private val hasher = mockk<PasswordHasher>()
    private val service = UserService(repo, tx, hasher)

    @Test
    fun `empty email throws INVALID_EMAIL`() = runTest {
        val ex = shouldThrow<ValidationException> { service.register("", "password123") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `whitespace-only email throws INVALID_EMAIL`() = runTest {
        val ex = shouldThrow<ValidationException> { service.register("   ", "password123") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `email without at-sign throws INVALID_EMAIL`() = runTest {
        val ex = shouldThrow<ValidationException> { service.register("notanemail", "password123") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `email without dot in domain throws INVALID_EMAIL`() = runTest {
        val ex = shouldThrow<ValidationException> { service.register("foo@bar", "password123") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `email with spaces throws INVALID_EMAIL`() = runTest {
        val ex = shouldThrow<ValidationException> { service.register("foo @bar.com", "password123") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `email exceeding RFC5321 max length throws INVALID_EMAIL`() = runTest {
        val tooLong = "a".repeat(250) + "@x.co"  // 256 chars
        val ex = shouldThrow<ValidationException> { service.register(tooLong, "password123") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `password shorter than 8 chars throws PASSWORD_TOO_WEAK`() = runTest {
        val ex = shouldThrow<ValidationException> { service.register("user@example.com", "short7!") }
        ex.errorCode shouldBe "PASSWORD_TOO_WEAK"
    }

    @Test
    fun `password longer than 256 chars throws PASSWORD_TOO_WEAK`() = runTest {
        val tooLong = "a".repeat(257)
        val ex = shouldThrow<ValidationException> { service.register("user@example.com", tooLong) }
        ex.errorCode shouldBe "PASSWORD_TOO_WEAK"
    }

    @Test
    fun `password exactly 8 chars passes validation gate`() = runTest {
        // Валидация пройдёт, но дальше mockk выбросит вне-плана при доступе к withTx.
        // Нас интересует только что НЕ ValidationException — любой другой Throwable означает,
        // что validation gate пропустил вход.
        val ex = runCatching { service.register("user@example.com", "abcdefgh") }
            .exceptionOrNull()
        (ex !is ValidationException) shouldBe true
    }
}

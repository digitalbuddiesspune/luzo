package com.craft.ludo.shared.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ApiErrorResponse(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
)

class DomainException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)

@RestControllerAdvice
class ApiSupport {
    @ExceptionHandler(DomainException::class)
    fun handleDomainException(exception: DomainException): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.status(exception.status)
            .body(
                ApiErrorResponse(
                    timestamp = Instant.now(),
                    status = exception.status.value(),
                    error = exception.status.reasonPhrase,
                    message = exception.message,
                ),
            )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(exception: IllegalArgumentException): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.badRequest()
            .body(
                ApiErrorResponse(
                    timestamp = Instant.now(),
                    status = HttpStatus.BAD_REQUEST.value(),
                    error = HttpStatus.BAD_REQUEST.reasonPhrase,
                    message = exception.message ?: "Bad request",
                ),
            )
    }
}

package com.brewslot.web

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/**
 * 모든 오류 응답을 RFC 9457 `application/problem+json` 으로 통일한다.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ProblemDetail> {
        val problem = problem(e.status, e.type, e.message)
        e.extensions.forEach { (k, v) -> problem.setProperty(k, v) }
        return ResponseEntity.status(e.status).body(problem)
    }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: OptimisticLockingFailureException): ResponseEntity<ProblemDetail> {
        log.info("optimistic lock conflict: {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(problem(HttpStatus.CONFLICT, "concurrent-modification", "다른 요청이 먼저 처리되었습니다. 다시 조회 후 시도해 주세요."))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(e: ConstraintViolationException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid-request", e.message ?: "invalid request"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid-request", e.message ?: "invalid request"))

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "요청 값이 올바르지 않습니다.")
        problem.setProperty(
            "violations",
            ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) },
        )
        return ResponseEntity.badRequest().body(problem)
    }

    companion object {
        const val TYPE_BASE = "https://brewslot.dev/problems/"

        fun problem(status: HttpStatus, type: String, detail: String): ProblemDetail =
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                this.type = URI.create(TYPE_BASE + type)
                this.title = status.reasonPhrase
            }
    }
}

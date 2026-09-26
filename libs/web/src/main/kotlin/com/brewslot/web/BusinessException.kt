package com.brewslot.web

import org.springframework.http.HttpStatus

/**
 * 도메인/애플리케이션 계층에서 발생한, 클라이언트가 이해하고 대응할 수 있는 오류.
 *
 * [type] 은 RFC 9457 Problem Details 의 `type` URI 끝 부분(slug)으로 사용되며
 * API 명세(docs/api)에 오류 카탈로그로 문서화되어 있다. 클라이언트는 메시지가 아니라 type 으로 분기한다.
 */
open class BusinessException(
    val type: String,
    val status: HttpStatus,
    override val message: String,
    val extensions: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

class NotFoundException(resource: String, id: Any) :
    BusinessException("not-found", HttpStatus.NOT_FOUND, "$resource($id) 를 찾을 수 없습니다.")

open class ConflictException(type: String, message: String, extensions: Map<String, Any?> = emptyMap()) :
    BusinessException(type, HttpStatus.CONFLICT, message, extensions)

open class UnprocessableException(type: String, message: String, extensions: Map<String, Any?> = emptyMap()) :
    BusinessException(type, HttpStatus.UNPROCESSABLE_ENTITY, message, extensions)

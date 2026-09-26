package com.brewslot.settlement.api

import com.brewslot.settlement.application.ReconciliationView
import com.brewslot.settlement.application.SettlementService
import com.brewslot.settlement.application.StatementView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class RunRequest(val businessDate: LocalDate)

@Tag(name = "Settlement")
@RestController
class SettlementController(private val settlement: SettlementService) {
    @Operation(summary = "정산 마감 실행 (멱등). 마감 후 도착한 과거 거래는 다음 마감에 이월된다")
    @PostMapping("/settlement-runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun close(@RequestBody request: RunRequest): List<StatementView> = settlement.close(request.businessDate)

    @Operation(summary = "매장 정산서 조회")
    @GetMapping("/stores/{storeId}/settlement-statements")
    fun statements(
        @PathVariable storeId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<StatementView> = settlement.statements(storeId, from, to)

    @Operation(summary = "PG 정산 파일과 원장 대사 실행")
    @PostMapping("/reconciliation-runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun reconcile(@RequestBody request: RunRequest): ReconciliationView = settlement.reconcile(request.businessDate)
}

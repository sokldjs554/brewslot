package com.brewslot.order.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * DDD 계층 규칙을 테스트로 고정한다.
 * 도메인 모델(스케줄러, 주문 상태기계, Saga 상태)은 프레임워크·인프라·API 를 몰라야 단위 테스트가 빠르고 재사용 가능하다.
 */
@AnalyzeClasses(packages = ["com.brewslot.order"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    val domainIsFrameworkFree: ArchRule = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "org.apache.kafka..",
            "java.sql..",
            "com.brewslot.messaging..",
        )

    @ArchTest
    val domainDoesNotDependOnOuterLayers: ArchRule = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infra..", "..api..", "..query..")

    @ArchTest
    val onlyApiLayerKnowsControllers: ArchRule = noClasses().that().resideInAnyPackage("..application..", "..infra..", "..domain..")
        .should().dependOnClassesThat().resideInAPackage("..api..")
}

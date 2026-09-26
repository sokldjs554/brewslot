package com.brewslot.web

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnWebApplication
@Import(ProblemDetailsAdvice::class)
class WebAutoConfiguration

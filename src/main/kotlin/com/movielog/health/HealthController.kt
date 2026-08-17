package com.movielog.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(
	val status: String,
	val commit: String,
)

@RestController
class HealthController(
	@Value("\${build.commit:unknown}") private val commit: String,
) {
	@GetMapping("/health")
	fun health(): HealthResponse = HealthResponse(status = "UP", commit = commit)
}

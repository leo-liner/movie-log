package com.movielog.health

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(HealthController::class)
@TestPropertySource(properties = ["build.commit=abc1234"])
class HealthControllerTest {

	@Autowired
	lateinit var mockMvc: MockMvc

	@Test
	fun `health 엔드포인트는 상태와 커밋 해시를 반환한다`() {
		mockMvc.get("/health")
			.andExpect {
				status { isOk() }
				jsonPath("$.status") { value("UP") }
				jsonPath("$.commit") { value("abc1234") }
			}
	}
}

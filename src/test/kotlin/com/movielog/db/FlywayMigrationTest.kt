package com.movielog.db

import com.movielog.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class FlywayMigrationTest {

	@Autowired
	lateinit var dataSource: DataSource

	private fun <T> queryFirst(sql: String, extract: (java.sql.ResultSet) -> T): T =
		dataSource.connection.use { conn ->
			conn.createStatement().use { statement ->
				statement.executeQuery(sql).use { rs ->
					assertTrue(rs.next(), "쿼리 결과가 비어 있다: $sql")
					extract(rs)
				}
			}
		}

	@Test
	fun `pg_trgm 확장이 설치된다`() {
		val count = queryFirst(
			"SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'",
		) { it.getInt(1) }

		assertEquals(1, count)
	}

	@Test
	fun `flyway 이력 테이블에 V1이 성공으로 기록된다`() {
		val success = queryFirst(
			"SELECT success FROM flyway_schema_history WHERE version = '1'",
		) { it.getBoolean(1) }

		assertTrue(success)
	}
}

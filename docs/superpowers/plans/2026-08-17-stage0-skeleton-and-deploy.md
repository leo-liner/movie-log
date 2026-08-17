# 0단계: 프로젝트 골격 + 배포 파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `main`에 push하면 몇 분 안에 `https://<ip>.sslip.io/health`가 방금 커밋의 해시를 반환하는 배포 파이프라인을 완성한다.

**Architecture:** Kotlin + Spring Boot 애플리케이션을 ARM64 컨테이너 이미지로 빌드해 GHCR에 올리고, Oracle Cloud Always Free 인스턴스 한 대에서 Docker Compose로 Caddy·app·PostgreSQL·Redis를 함께 운영한다. GitHub Actions가 테스트·빌드·배포·헬스체크·롤백을 담당한다.

**Tech Stack:** Kotlin, Spring Boot 4.1, Gradle 9 (Kotlin DSL), JDK 25, PostgreSQL 16, Redis 7, Flyway, Testcontainers, Docker Compose, Caddy, GitHub Actions, Oracle Cloud Infrastructure

**Spec:** `docs/superpowers/specs/2026-08-17-movie-log-design.md`

## Global Constraints

- **운영 비용 0원.** 유료 리소스를 만들지 않는다. 무료 한도가 있는 항목은 한도 안에서만 쓴다.
- **GitHub 레포지토리는 퍼블릭.** GHCR 이미지 저장, GitHub Actions 실행 시간, `ubuntu-24.04-arm` 러너가 모두 퍼블릭 레포지토리에서만 무료다. `ubuntu-24.04-arm` 라벨은 프라이빗 레포지토리에서 워크플로 자체가 실패한다.
- **OCI 리소스는 홈 리전에만 만든다.** Always Free는 테넌시의 홈 리전에서만 적용되며, 홈 리전은 계정 생성 시 정해져 이후 변경할 수 없다. 춘천(`ap-chuncheon-1`) 또는 서울(`ap-seoul-1`)을 고른다.
- **서버 아키텍처는 ARM64.** 컨테이너 이미지는 `linux/arm64`로 빌드해야 한다.
- **서버에서 Gradle 빌드를 하지 않는다.** 빌드는 GitHub Actions에서 끝내고 서버는 이미지를 받아 실행만 한다.
- **OCI 블록 볼륨 총 200GB, 아웃바운드 월 10TB, 컴퓨트 월 1,500 OCPU 시간 / 9,000 GB 시간.**
- 애플리케이션 패키지 루트는 `com.movielog`.
- 커밋 메시지는 한글로 쓰고 Conventional Commits 접두사(`feat:`, `chore:`, `docs:`, `ci:`)를 붙인다.

## 실행 순서 조정 (2026-08-17)

로컬에 Docker를 설치하지 않기로 하여 태스크 순서를 바꾼다.

**1 → 2 → 3 → 7 → 4 → 5 → 6 → 8**

서버가 없어도 CI는 돌릴 수 있으므로 Task 7을 앞으로 당긴다. 1차 목표는 Task 7까지, 즉 GitHub Actions가 테스트를 돌리고 ARM64 이미지를 GHCR에 올리는 것까지다. 서버 준비(Task 4~6)와 자동 배포(Task 8)는 그 뒤에 이어간다.

**로컬에서 되는 것**: 컴파일, `HealthControllerTest`(`@WebMvcTest`라 DataSource를 안 띄운다). Gradle 툴체인이 JDK 25를 자동으로 받는다.

**로컬에서 안 되는 것**: `FlywayMigrationTest`(Testcontainers가 Docker를 요구), 이미지 빌드, `compose.yaml` 기동. 전부 CI에서 확인한다. 해당 스텝의 로컬 검증 지시는 건너뛰고 CI 결과로 대체한다.

Task 6 Step 4(로컬 이미지를 서버로 전송)는 실행하지 않는다. Task 7이 먼저 끝나 GHCR에 이미지가 있으므로, 서버가 그것을 직접 받는다.

---

## 파일 구조

| 경로 | 책임 |
|---|---|
| `build.gradle.kts` | 의존성과 빌드 설정. plain jar 비활성화 |
| `settings.gradle.kts` | 프로젝트 이름 |
| `src/main/kotlin/com/movielog/MovieLogApplication.kt` | 애플리케이션 진입점 |
| `src/main/kotlin/com/movielog/health/HealthController.kt` | `/health` 응답 |
| `src/main/resources/application.yml` | 프로파일 공통 설정 |
| `src/main/resources/application-local.yml` | 로컬 DB 접속 정보 |
| `src/main/resources/application-prod.yml` | 운영 설정. 값은 환경 변수로 주입 |
| `src/main/resources/db/migration/V1__enable_extensions.sql` | 첫 Flyway 마이그레이션 |
| `src/test/kotlin/com/movielog/health/HealthControllerTest.kt` | `/health` 응답 검증 |
| `src/test/kotlin/com/movielog/db/FlywayMigrationTest.kt` | Testcontainers로 마이그레이션 검증 |
| `Dockerfile` | ARM64 런타임 이미지. layered jar 추출 |
| `compose.yaml` | 로컬 개발용 PostgreSQL·Redis |
| `deploy/compose.prod.yaml` | 서버용 전체 스택 |
| `deploy/Caddyfile` | 리버스 프록시와 HTTPS |
| `deploy/setup-server.sh` | 서버 초기 설정 |
| `.github/workflows/deploy.yml` | 테스트 → 이미지 빌드·푸시 → 배포 → 헬스체크 → 롤백 |

---

### Task 1: Spring Boot 골격과 `/health` 엔드포인트

**Files:**
- Create: `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradle/wrapper/*` (프로젝트 생성기가 만듦)
- Create: `src/main/kotlin/com/movielog/MovieLogApplication.kt`
- Create: `src/main/kotlin/com/movielog/health/HealthController.kt`
- Create: `src/main/resources/application.yml`
- Test: `src/test/kotlin/com/movielog/health/HealthControllerTest.kt`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `GET /health` → `200 OK`, 본문 `{"status":"UP","commit":"<해시>"}`. 커밋 해시는 환경 변수 `BUILD_COMMIT`에서 읽으며 없으면 `"unknown"`. 이후 Task 3·6·8이 이 계약에 의존한다.

- [ ] **Step 1: 프로젝트 골격 생성**

프로젝트 루트에서 실행한다. 버전 조합을 손으로 맞추면 틀리기 쉬우므로 생성기가 정하게 둔다.

```bash
cd ~/Documents/pe/movie-log
curl -sSf https://start.spring.io/starter.zip \
  -d type=gradle-project-kotlin \
  -d language=kotlin \
  -d bootVersion=4.1.0 \
  -d javaVersion=25 \
  -d groupId=com.movielog \
  -d artifactId=movie-log \
  -d name=movie-log \
  -d packageName=com.movielog \
  -d dependencies=web,data-jpa,postgresql,flyway,testcontainers \
  -o starter.zip
unzip -o starter.zip -x 'README.md' 'HELP.md' '.gitignore'
rm starter.zip
```

`.gitignore`가 없으면 아래 내용으로 만든다.

```
build/
.gradle/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.env
.DS_Store
```

- [ ] **Step 2: plain jar 비활성화**

Gradle은 기본적으로 실행 가능한 `movie-log-0.0.1-SNAPSHOT.jar`와 라이브러리용 `movie-log-0.0.1-SNAPSHOT-plain.jar`를 함께 만든다. Task 3의 Dockerfile이 `build/libs/*.jar` 패턴으로 jar를 집으므로 두 개가 있으면 빌드가 실패한다.

`build.gradle.kts` 끝에 추가한다.

```kotlin
tasks.named<Jar>("jar") {
    enabled = false
}
```

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/kotlin/com/movielog/health/HealthControllerTest.kt`:

```kotlin
package com.movielog.health

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
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
```

`@WebMvcTest`는 웹 계층만 올리고 `DataSource`와 JPA를 초기화하지 않는다. 아직 PostgreSQL이 없어도 이 테스트는 돌아간다.

- [ ] **Step 4: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests '*HealthControllerTest*'`
Expected: 컴파일 실패. `HealthController` 클래스를 찾을 수 없다는 오류.

- [ ] **Step 5: 최소 구현 작성**

`src/main/kotlin/com/movielog/health/HealthController.kt`:

```kotlin
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
```

`src/main/resources/application.yml`을 아래 내용으로 덮어쓴다.

```yaml
spring:
  application:
    name: movie-log
  jpa:
    open-in-view: false

build:
  commit: ${BUILD_COMMIT:unknown}
```

`open-in-view`를 끄는 이유는 성능이 아니라 학습 때문이다. 기본값인 `true`는 HTTP 응답이 끝날 때까지 영속성 컨텍스트를 열어두어 지연 로딩이 컨트롤러에서도 조용히 동작한다. 그러면 2단계에서 재현할 N+1이 눈에 띄지 않고 DB 커넥션도 필요 이상으로 오래 붙잡힌다.

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*HealthControllerTest*'`
Expected: PASS (1 test)

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat: Spring Boot 골격과 health 엔드포인트 추가"
```

---

### Task 2: PostgreSQL 연결과 Flyway 마이그레이션

**Files:**
- Create: `src/main/resources/application-local.yml`
- Create: `src/main/resources/application-prod.yml`
- Create: `src/main/resources/db/migration/V1__enable_extensions.sql`
- Create: `compose.yaml`
- Test: `src/test/kotlin/com/movielog/db/FlywayMigrationTest.kt`
- Modify: `build.gradle.kts` (Testcontainers PostgreSQL 모듈 추가)

**Interfaces:**
- Consumes: Task 1의 애플리케이션 골격
- Produces: `local`·`prod` 두 프로파일. `prod`는 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD` 환경 변수를 요구한다. Task 6의 `compose.prod.yaml`이 이 변수 이름에 의존한다.

- [ ] **Step 1: 로컬 데이터베이스 구성 작성**

`compose.yaml`:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: movielog
      POSTGRES_USER: movielog
      POSTGRES_PASSWORD: movielog
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U movielog"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  pgdata:
```

로컬에서는 애플리케이션을 IDE나 `./gradlew bootRun`으로 띄우고, 데이터베이스만 컨테이너로 쓴다. 애플리케이션 컨테이너는 Task 3에서 따로 검증한다.

- [ ] **Step 2: 프로파일별 설정 작성**

`src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/movielog
    username: movielog
    password: movielog
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

`src/main/resources/application-prod.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

`ddl-auto: validate`는 Hibernate가 스키마를 절대 바꾸지 못하게 막는다. 스키마 변경 경로를 Flyway 하나로 강제하기 위해서다.

- [ ] **Step 3: Testcontainers 의존성 추가**

`build.gradle.kts`의 `dependencies` 블록에 추가한다. 생성기가 이미 넣은 항목이 있으면 중복해서 넣지 않는다.

```kotlin
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:postgresql")
```

- [ ] **Step 4: 실패하는 통합 테스트 작성**

`src/test/kotlin/com/movielog/db/FlywayMigrationTest.kt`:

```kotlin
package com.movielog.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
class FlywayMigrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16")
    }

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `pg_trgm 확장이 설치된다`() {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'",
            Int::class.java,
        )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `flyway 이력 테이블에 V1이 성공으로 기록된다`() {
        val success = jdbcTemplate.queryForObject(
            "SELECT success FROM flyway_schema_history WHERE version = '1'",
            Boolean::class.java,
        )
        assertThat(success).isTrue()
    }
}
```

`@ServiceConnection`은 컨테이너가 띄운 PostgreSQL의 접속 정보를 Spring의 `DataSource` 설정에 자동으로 연결한다. 접속 URL을 손으로 넘기는 코드가 필요 없다.

- [ ] **Step 5: 테스트가 실패하는지 확인**

Docker가 실행 중이어야 한다. 마이그레이션 파일이 아직 없으므로 두 테스트 모두 실패한다.

Run: `./gradlew test --tests '*FlywayMigrationTest*'`
Expected: FAIL. `pg_trgm` 조회 결과가 0이고, `flyway_schema_history`에 버전 1 행이 없어 `EmptyResultDataAccessException`이 발생한다.

- [ ] **Step 6: 첫 마이그레이션 작성**

`src/main/resources/db/migration/V1__enable_extensions.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

`pg_trgm`은 문자열 유사도 기반 검색을 지원하는 PostgreSQL 확장이다. 1단계에서 영화 제목 부분 일치 검색에 쓴다. 지금 넣는 이유는 Flyway가 실제로 무언가를 적용하는지 검증할 대상이 필요해서다.

- [ ] **Step 7: 전체 테스트 통과 확인**

```bash
docker compose up -d
./gradlew test
```

Expected: PASS (3 tests — `HealthControllerTest` 1개, `FlywayMigrationTest` 2개)

- [ ] **Step 8: 로컬 실행 확인**

```bash
SPRING_PROFILES_ACTIVE=local BUILD_COMMIT=$(git rev-parse --short HEAD) ./gradlew bootRun
```

다른 터미널에서:

```bash
curl -s localhost:8080/health
```

Expected: `{"status":"UP","commit":"<현재 커밋 해시>"}`

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "feat: PostgreSQL 연결과 Flyway 마이그레이션 추가"
```

---

### Task 3: 컨테이너 이미지

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

**Interfaces:**
- Consumes: Task 1의 `bootJar` 산출물, Task 2의 `prod` 프로파일
- Produces: `linux/arm64` 이미지. 진입점은 `java -jar application.jar`. 환경 변수 `SPRING_PROFILES_ACTIVE`, `BUILD_COMMIT`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JAVA_TOOL_OPTIONS`를 받는다. Task 6·8이 이 이미지를 실행한다.

- [ ] **Step 1: `.dockerignore` 작성**

빌드 컨텍스트에서 불필요한 파일을 빼면 이미지 빌드가 빨라지고, 실수로 `.env` 같은 파일이 이미지에 들어가는 것도 막는다.

```
.git
.gradle
build/tmp
build/reports
.idea
.env
docs
*.md
```

`build/libs`는 제외하면 안 된다. jar가 거기 있다.

- [ ] **Step 2: Dockerfile 작성**

```dockerfile
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /builder
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
```

`-Djarmode=tools ... extract --layers`는 하나로 뭉친 jar를 네 덩어리로 풀어낸다. 의존성은 거의 안 바뀌고 애플리케이션 코드는 매 커밋 바뀌므로, `COPY`를 나눠 쓰면 앞의 세 레이어가 캐시되어 코드만 고친 커밋의 이미지 전송량이 크게 줄어든다.

실행 이미지는 JRE만 담은 것을 쓴다. JDK에는 컴파일러와 개발 도구가 들어 있어 실행에는 필요 없다.

- [ ] **Step 3: 이미지 빌드**

```bash
./gradlew clean bootJar
docker build --platform linux/arm64 -t movie-log:test .
```

Expected: 빌드 성공. Apple Silicon 개발 머신이면 네이티브라 빠르다.

- [ ] **Step 4: 컨테이너로 띄워 확인**

`compose.yaml`의 PostgreSQL이 떠 있어야 한다. 컨테이너 안에서 호스트의 PostgreSQL에 접속하려면 `host.docker.internal`을 쓴다.

```bash
docker compose up -d
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e BUILD_COMMIT=$(git rev-parse --short HEAD) \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/movielog \
  -e DB_USERNAME=movielog \
  -e DB_PASSWORD=movielog \
  movie-log:test
```

다른 터미널에서:

```bash
curl -s localhost:8080/health
```

Expected: `{"status":"UP","commit":"<현재 커밋 해시>"}`

확인 후 컨테이너를 `Ctrl+C`로 멈춘다.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat: ARM64 컨테이너 이미지 빌드 구성 추가"
```

---

### Task 4: Oracle Cloud 인스턴스 준비

이 태스크는 코드가 없고 OCI 콘솔에서 하는 수동 작업이다. 자동화 가치가 낮고(한 번만 한다) 계정 생성은 자동화가 불가능하다.

**Files:** 없음

**Interfaces:**
- Produces: 공인 IP 주소 하나, `ubuntu` 사용자로 SSH 접속 가능한 ARM64 서버. Task 5·6·8이 이 서버를 대상으로 한다.

- [ ] **Step 1: OCI 계정 생성**

https://www.oracle.com/cloud/free/ 에서 가입한다.

**홈 리전을 춘천(`ap-chuncheon-1`) 또는 서울(`ap-seoul-1`)로 고른다.** 이 선택은 나중에 바꿀 수 없고, Always Free 리소스는 홈 리전에서만 무료다. 다른 리전을 고르면 이 프로젝트의 비용 0 전제가 깨진다.

가입에 신용카드가 필요하지만 Always Free 리소스만 쓰는 한 청구되지 않는다. 가입 직후 받는 30일 무료 크레딧이 소진되어도 Always Free 리소스는 계속 동작한다.

- [ ] **Step 2: SSH 키 쌍 생성**

```bash
ssh-keygen -t ed25519 -f ~/.ssh/movie-log-oci -C "movie-log-oci" -N ""
```

`~/.ssh/movie-log-oci.pub`의 내용을 인스턴스 생성 화면에 붙여넣는다.

- [ ] **Step 3: 컴퓨트 인스턴스 생성**

콘솔의 Compute → Instances → Create instance에서 다음을 지정한다.

- Image: Canonical Ubuntu (LTS 최신)
- Shape: `VM.Standard.A1.Flex`, **2 OCPU / 12 GB**
- Boot volume: 50 GB
- Networking: 새 VCN, 퍼블릭 서브넷, **공인 IPv4 주소 할당**
- SSH keys: Step 2의 공개 키

**"Out of capacity" 오류가 나면** 가용성 도메인을 바꾸거나 시간을 두고 재시도한다. 계속 실패하면 Always Free에 함께 포함된 AMD `VM.Standard.E2.1.Micro`(1 OCPU / 1 GB) 2대로 시작할 수 있다. 다만 1GB에서는 JVM 힙을 512MB 정도로 낮추고 스왑 2GB를 잡아야 하며, Task 5의 스왑 단계와 Task 6의 메모리 설정을 그 기준으로 조정해야 한다.

- [ ] **Step 4: 시큐리티 리스트에 80·443 개방**

Networking → Virtual Cloud Networks → 해당 VCN → Security Lists → Default Security List → Add Ingress Rules.

두 규칙을 추가한다.

- Source CIDR `0.0.0.0/0`, IP Protocol TCP, Destination Port Range `80`
- Source CIDR `0.0.0.0/0`, IP Protocol TCP, Destination Port Range `443`

OCI는 클라우드 쪽 시큐리티 리스트와 인스턴스 안의 `iptables` 두 겹으로 막혀 있다. 여기서 여는 건 첫 번째 겹이고, 두 번째는 Task 5에서 연다. 한쪽만 열면 접속되지 않는다.

- [ ] **Step 5: 공인 IP를 예약 IP로 전환**

Networking → IP Management → Reserved Public IPs에서 인스턴스에 붙은 임시 IP를 예약 IP로 바꾼다. 임시 IP는 인스턴스를 중지·시작하면 바뀌고, 그러면 sslip.io 도메인 주소도 바뀌어 HTTPS 인증서를 다시 받아야 한다.

- [ ] **Step 6: SSH 접속 확인**

```bash
ssh -i ~/.ssh/movie-log-oci ubuntu@<공인_IP>
```

Expected: 로그인 성공. 접속되면 `exit`으로 나온다.

- [ ] **Step 7: 예산 알림 설정**

Billing → Budgets에서 월 1달러 예산을 만들고 100% 도달 시 이메일 알림을 켠다. 실수로 유료 리소스를 만들었을 때 알아채기 위한 안전장치다.

- [ ] **Step 8: IP 주소 기록**

이후 태스크에서 반복해서 쓴다. 예를 들어 공인 IP가 `152.67.10.20`이면 도메인은 `152-67-10-20.sslip.io`가 된다.

---

### Task 5: 서버 초기 설정

**Files:**
- Create: `deploy/setup-server.sh`

**Interfaces:**
- Consumes: Task 4의 서버
- Produces: Docker가 설치되고 80·443이 열린 서버, `docker` 그룹에 속한 `deploy` 사용자, `/opt/movie-log` 디렉터리. Task 6·8이 `deploy` 사용자로 접속한다.

- [ ] **Step 1: 초기 설정 스크립트 작성**

`deploy/setup-server.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "==> Docker 설치"
curl -fsSL https://get.docker.com | sudo sh

echo "==> 배포 전용 사용자 생성"
if ! id deploy &>/dev/null; then
  sudo useradd --create-home --shell /bin/bash deploy
fi
sudo usermod -aG docker deploy

echo "==> 배포 디렉터리 생성"
sudo mkdir -p /opt/movie-log
sudo chown deploy:deploy /opt/movie-log

echo "==> 방화벽에 80, 443 개방"
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo apt-get update
sudo apt-get install -y iptables-persistent
sudo netfilter-persistent save

echo "==> 완료"
```

Oracle의 Ubuntu 이미지는 기본 `iptables` 규칙에서 22번만 열어두고 나머지를 거부한다. `-I INPUT 6`은 거부 규칙 앞에 삽입하기 위한 위치다. `netfilter-persistent save`를 하지 않으면 재부팅 시 규칙이 사라진다.

`deploy` 사용자를 따로 만드는 이유는 배포용 SSH 키의 권한을 좁히기 위해서다. 이 키가 유출되어도 `sudo` 권한이 없어 서버 전체를 장악당하지는 않는다.

- [ ] **Step 2: 스크립트 실행**

```bash
scp -i ~/.ssh/movie-log-oci deploy/setup-server.sh ubuntu@<공인_IP>:/tmp/
ssh -i ~/.ssh/movie-log-oci ubuntu@<공인_IP> "bash /tmp/setup-server.sh"
```

Expected: 마지막 줄에 `==> 완료`.

- [ ] **Step 3: 배포용 SSH 키 쌍 생성과 등록**

Task 4의 개인 키는 사람이 쓰는 것이므로 GitHub Actions에 주지 않는다. 배포 전용 키를 따로 만든다.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/movie-log-deploy -C "movie-log-deploy" -N ""
ssh -i ~/.ssh/movie-log-oci ubuntu@<공인_IP> \
  "sudo -u deploy mkdir -p /home/deploy/.ssh && \
   echo '$(cat ~/.ssh/movie-log-deploy.pub)' | sudo -u deploy tee -a /home/deploy/.ssh/authorized_keys && \
   sudo -u deploy chmod 700 /home/deploy/.ssh && \
   sudo -u deploy chmod 600 /home/deploy/.ssh/authorized_keys"
```

- [ ] **Step 4: 배포 사용자로 접속 확인**

```bash
ssh -i ~/.ssh/movie-log-deploy deploy@<공인_IP> "docker ps"
```

Expected: 빈 컨테이너 목록이 출력된다. `permission denied` 오류가 나면 `docker` 그룹 반영을 위해 세션을 새로 열어야 한다.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "chore: 서버 초기 설정 스크립트 추가"
```

---

### Task 6: 운영 스택 구성과 첫 수동 배포

자동 배포를 만들기 전에 손으로 한 번 올려 본다. 자동화가 실패했을 때 원인이 배포 스크립트인지 스택 구성인지 가려내기 위해서다.

**Files:**
- Create: `deploy/compose.prod.yaml`
- Create: `deploy/Caddyfile`

**Interfaces:**
- Consumes: Task 3의 이미지, Task 5의 서버
- Produces: `https://<ip-하이픈>.sslip.io/health`가 응답하는 상태. `/opt/movie-log/.env`가 `DB_PASSWORD`와 `SITE_ADDRESS`를, `/opt/movie-log/.env.image`가 `APP_IMAGE`와 `BUILD_COMMIT`을 담는다. Task 8이 `.env.image`만 갱신한다.

- [ ] **Step 1: Caddyfile 작성**

`deploy/Caddyfile`:

```
{$SITE_ADDRESS} {
	reverse_proxy app:8080
}
```

Caddy는 여기 적힌 도메인으로 Let's Encrypt 인증서를 자동 발급하고 만료 전에 갱신한다. 별도 설정이 필요 없다.

- [ ] **Step 2: 운영 compose 작성**

`deploy/compose.prod.yaml`:

```yaml
services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    environment:
      SITE_ADDRESS: ${SITE_ADDRESS}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    mem_limit: 128m
    depends_on:
      - app

  app:
    image: ${APP_IMAGE}
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: prod
      BUILD_COMMIT: ${BUILD_COMMIT}
      DB_URL: jdbc:postgresql://postgres:5432/movielog
      DB_USERNAME: movielog
      DB_PASSWORD: ${DB_PASSWORD}
      JAVA_TOOL_OPTIONS: "-Xms3g -Xmx3g"
    mem_limit: 4g
    depends_on:
      postgres:
        condition: service_healthy

  postgres:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_DB: movielog
      POSTGRES_USER: movielog
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    command: ["postgres", "-c", "shared_buffers=512MB"]
    mem_limit: 2g
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U movielog"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: ["redis-server", "--maxmemory", "384mb", "--maxmemory-policy", "allkeys-lru"]
    mem_limit: 512m

volumes:
  pgdata:
  caddy_data:
  caddy_config:
```

`JAVA_TOOL_OPTIONS`의 `-Xms3g -Xmx3g`는 두 가지를 동시에 한다. 힙을 시작 시점에 확보해 런타임 확장 비용을 없애고, 메모리 사용률을 12GB의 20% 위로 올려 Oracle의 유휴 인스턴스 회수 조건 하나를 깬다. `mem_limit`을 힙보다 큰 4g로 잡은 이유는 메타스페이스·스레드 스택·다이렉트 버퍼가 힙 바깥에 따로 잡히기 때문이다.

- [ ] **Step 3: GHCR 이미지를 퍼블릭으로 만들 준비**

Task 8에서 GitHub Actions가 이미지를 GHCR에 올린다. 기본 가시성이 private이면 서버에서 `docker pull`할 때 인증이 필요해 배포 파이프라인에 시크릿이 하나 더 붙는다. 첫 푸시 이후 GitHub 레포지토리 → Packages → 해당 패키지 → Package settings → Change visibility에서 **Public**으로 바꾼다.

이 단계에서는 아직 이미지가 없으므로, 지금은 로컬에서 만든 이미지를 직접 서버로 옮겨 검증한다.

- [ ] **Step 4: 로컬 이미지를 서버로 전송**

```bash
./gradlew clean bootJar
docker build --platform linux/arm64 -t movie-log:manual .
docker save movie-log:manual | gzip | \
  ssh -i ~/.ssh/movie-log-deploy deploy@<공인_IP> "gunzip | docker load"
```

- [ ] **Step 5: 설정 파일 전송과 환경 변수 작성**

```bash
scp -i ~/.ssh/movie-log-deploy deploy/compose.prod.yaml deploy/Caddyfile \
  deploy@<공인_IP>:/opt/movie-log/
```

서버에 접속해 환경 변수 파일을 만든다. `<ip-하이픈>`은 공인 IP의 점을 하이픈으로 바꾼 형태다(예: `152.67.10.20` → `152-67-10-20`).

```bash
ssh -i ~/.ssh/movie-log-deploy deploy@<공인_IP>
cd /opt/movie-log
cat > .env <<EOF
DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')
SITE_ADDRESS=<ip-하이픈>.sslip.io
EOF
cat > .env.image <<EOF
APP_IMAGE=movie-log:manual
BUILD_COMMIT=manual
EOF
chmod 600 .env
```

- [ ] **Step 6: 스택 기동**

서버에서 실행한다.

```bash
cd /opt/movie-log
docker compose -f compose.prod.yaml --env-file .env --env-file .env.image up -d
docker compose -f compose.prod.yaml ps
```

Expected: `caddy`, `app`, `postgres`, `redis` 네 컨테이너가 실행 중.

- [ ] **Step 7: HTTPS 응답 확인**

인증서 발급에 10~30초 걸린다. 로컬 머신에서 실행한다.

```bash
curl -s https://<ip-하이픈>.sslip.io/health
```

Expected: `{"status":"UP","commit":"manual"}`

실패하면 순서대로 확인한다.

```bash
# 서버에서
docker compose -f compose.prod.yaml logs caddy   # 인증서 발급 실패 여부
docker compose -f compose.prod.yaml logs app     # 애플리케이션 기동 실패 여부
sudo iptables -L INPUT -n --line-numbers          # 80, 443 규칙 존재 여부
```

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat: 운영 스택 compose와 Caddy 구성 추가"
```

---

### Task 7: GitHub Actions로 테스트와 이미지 빌드

**Files:**
- Create: `.github/workflows/deploy.yml`

**Interfaces:**
- Consumes: Task 1·2의 테스트, Task 3의 Dockerfile
- Produces: `ghcr.io/<owner>/movie-log:<커밋해시>` 태그의 `linux/arm64` 이미지. Task 8이 이 태그를 서버에서 받아 실행한다.

- [ ] **Step 1: GitHub 레포지토리 생성과 연결**

**퍼블릭으로 만든다.** 프라이빗이면 `ubuntu-24.04-arm` 러너 라벨이 동작하지 않아 워크플로가 실패하고, GHCR 저장과 Actions 실행 시간도 무료 한도에 걸린다.

```bash
gh repo create movie-log --public --source=. --remote=origin
```

- [ ] **Step 2: 워크플로 작성**

`.github/workflows/deploy.yml`:

```yaml
name: Deploy

on:
  push:
    branches: [main]

concurrency:
  group: deploy
  cancel-in-progress: false

jobs:
  test:
    runs-on: ubuntu-24.04-arm
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: 테스트 실행
        run: ./gradlew test

  build:
    needs: test
    runs-on: ubuntu-24.04-arm
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v4
      - name: jar 빌드
        run: ./gradlew bootJar
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/arm64
          push: true
          tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

러너를 `ubuntu-24.04-arm`으로 지정하는 이유는 서버가 ARM64이기 때문이다. 기본 `ubuntu-latest`는 x86_64라서 여기서 만든 이미지는 서버에서 실행되지 않는다. QEMU 에뮬레이션으로 크로스 빌드할 수도 있지만 몇 배 느리다.

`concurrency` 그룹을 지정하면 배포가 겹치지 않는다. `cancel-in-progress: false`는 진행 중인 배포를 중간에 끊지 않기 위해서다.

Testcontainers를 쓰는 통합 테스트는 러너의 Docker를 사용한다. GitHub 호스티드 러너에는 Docker가 설치되어 있어 추가 설정이 필요 없다.

- [ ] **Step 3: 푸시하고 워크플로 실행 확인**

```bash
git add -A
git commit -m "ci: 테스트와 ARM64 이미지 빌드 워크플로 추가"
git push -u origin main
gh run watch
```

Expected: `test`와 `build` 두 잡이 성공.

- [ ] **Step 4: 이미지 확인과 퍼블릭 전환**

```bash
gh api "/users/$(gh api /user --jq .login)/packages/container/movie-log/versions" --jq '.[0].metadata.container.tags'
```

Expected: 방금 커밋 해시가 태그로 나온다.

GitHub 웹에서 Packages → `movie-log` → Package settings → Change visibility → **Public**으로 바꾼다. 그래야 서버가 인증 없이 이미지를 받을 수 있다.

- [ ] **Step 5: 서버에서 이미지 수신 확인**

```bash
ssh -i ~/.ssh/movie-log-deploy deploy@<공인_IP> \
  "docker pull ghcr.io/<owner>/movie-log:$(git rev-parse HEAD)"
```

Expected: 이미지를 정상적으로 받는다. `denied` 오류가 나면 Step 4의 가시성 전환이 안 된 것이다.

---

### Task 8: 자동 배포와 롤백

**Files:**
- Modify: `.github/workflows/deploy.yml` (`deploy` 잡 추가)

**Interfaces:**
- Consumes: Task 7의 이미지, Task 6의 서버 스택
- Produces: 0단계 완료 조건. `main`에 push하면 `https://<ip-하이픈>.sslip.io/health`가 해당 커밋의 전체 해시를 반환한다.

- [ ] **Step 1: GitHub Secrets 등록**

```bash
gh secret set DEPLOY_SSH_KEY < ~/.ssh/movie-log-deploy
gh secret set SERVER_HOST --body "<공인_IP>"
gh secret set SITE_ADDRESS --body "<ip-하이픈>.sslip.io"
```

`DEPLOY_SSH_KEY`는 Task 5에서 만든 배포 전용 키다. 개인 SSH 키를 넣지 않는다.

- [ ] **Step 2: `deploy` 잡 추가**

`.github/workflows/deploy.yml`의 `build` 잡 아래에 이어 붙인다.

```yaml
  deploy:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: SSH 키 설정
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/id_ed25519
          chmod 600 ~/.ssh/id_ed25519
          ssh-keyscan -H "${{ secrets.SERVER_HOST }}" >> ~/.ssh/known_hosts

      - name: 현재 배포 상태 백업
        run: |
          ssh deploy@${{ secrets.SERVER_HOST }} \
            "cd /opt/movie-log && cp .env.image .env.image.prev"

      - name: 새 이미지로 전환
        run: |
          ssh deploy@${{ secrets.SERVER_HOST }} "cd /opt/movie-log && \
            printf 'APP_IMAGE=ghcr.io/${{ github.repository }}:${{ github.sha }}\nBUILD_COMMIT=${{ github.sha }}\n' > .env.image && \
            docker compose -f compose.prod.yaml --env-file .env --env-file .env.image pull app && \
            docker compose -f compose.prod.yaml --env-file .env --env-file .env.image up -d app"

      - name: 헬스체크
        run: |
          for i in $(seq 1 30); do
            actual=$(curl -sf "https://${{ secrets.SITE_ADDRESS }}/health" | jq -r '.commit' 2>/dev/null || true)
            if [ "$actual" = "${{ github.sha }}" ]; then
              echo "배포 확인: $actual"
              exit 0
            fi
            echo "대기 중... ($i/30) 현재 응답: ${actual:-없음}"
            sleep 5
          done
          echo "헬스체크 실패: 150초 안에 새 커밋 해시가 반환되지 않았다"
          exit 1

      - name: 실패 시 롤백
        if: failure()
        run: |
          ssh deploy@${{ secrets.SERVER_HOST }} "cd /opt/movie-log && \
            if [ -f .env.image.prev ]; then \
              mv .env.image.prev .env.image && \
              docker compose -f compose.prod.yaml --env-file .env --env-file .env.image up -d app && \
              echo '이전 버전으로 되돌렸다'; \
            fi"
          ssh deploy@${{ secrets.SERVER_HOST }} \
            "cd /opt/movie-log && docker compose -f compose.prod.yaml logs --tail=100 app"
```

헬스체크가 커밋 해시를 비교하는 게 핵심이다. 단순히 200 응답만 확인하면 새 컨테이너가 뜨지 못해 **이전 버전이 계속 응답하는 상황**을 성공으로 오인한다. 해시를 비교하면 실제로 새 이미지가 서비스 중인지 확인된다.

`up -d app`으로 `app` 서비스만 다시 올린다. PostgreSQL과 Caddy는 건드릴 이유가 없고, 데이터베이스 컨테이너를 매 배포마다 재시작하면 불필요한 다운타임이 생긴다.

- [ ] **Step 3: 푸시하고 배포 확인**

```bash
git add -A
git commit -m "ci: 자동 배포와 롤백 추가"
git push
gh run watch
```

Expected: `test` → `build` → `deploy` 세 잡이 순서대로 성공하고, 헬스체크 스텝에 `배포 확인: <커밋해시>`가 찍힌다.

- [ ] **Step 4: 외부에서 최종 확인**

```bash
curl -s "https://<ip-하이픈>.sslip.io/health"
```

Expected: `{"status":"UP","commit":"<방금 push한 커밋의 전체 해시>"}`

- [ ] **Step 5: 롤백이 동작하는지 확인**

일부러 깨뜨려 본다. `HealthController`의 `/health` 매핑을 `/healthz`로 잠시 바꾸고 push한다.

```bash
git commit -am "test: 롤백 검증용 일부러 깨뜨리기"
git push
gh run watch
```

Expected: 헬스체크가 150초 동안 실패한 뒤 롤백 스텝이 실행되고, 워크플로는 실패로 끝난다. 롤백 직후 `curl`을 하면 **이전 커밋의 해시**가 반환된다.

확인했으면 되돌린다.

```bash
git revert --no-edit HEAD
git push
gh run watch
```

Expected: 정상 배포되고 최신 커밋 해시가 반환된다.

- [ ] **Step 6: 0단계 완료 기록**

```bash
git add -A
git commit --allow-empty -m "docs: 0단계 완료 - 배포 파이프라인 동작 확인"
git push
```

---

## 0단계 완료 조건 점검표

- [ ] `main`에 push하면 자동으로 테스트가 돌고, 실패하면 배포되지 않는다
- [ ] ARM64 이미지가 GHCR에 커밋 해시 태그로 올라간다
- [ ] 서버가 새 이미지를 받아 실행하고, `https://<ip-하이픈>.sslip.io/health`가 해당 커밋 해시를 반환한다
- [ ] 새 버전이 뜨지 못하면 자동으로 이전 버전으로 되돌아간다
- [ ] HTTPS 인증서가 자동 발급되어 브라우저에서 경고가 없다
- [ ] OCI 콘솔의 청구 금액이 0원이다

## 다음 단계

1단계(인증, TMDB 검색·캐싱, 감상 기록)의 상세 설계를 설계 문서에 덧붙인 뒤 계획을 작성한다.

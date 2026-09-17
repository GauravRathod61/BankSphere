# BankSphere Backend

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=java" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot-4.1.0-green?style=flat-square&logo=spring-boot" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/PostgreSQL-17-blue?style=flat-square&logo=postgresql" alt="PostgreSQL" />
  <img src="https://img.shields.io/badge/k6-v2.2.0-purple?style=flat-square&logo=k6" alt="k6" />
</p>

**BankSphere** is an enterprise-grade digital banking backend platform built with a modern Spring Boot microservices architecture: an API Gateway routing to independent Customer, Account, and Transaction services with comprehensive resilience, distributed transactions, security, observability, and capacity testing.

---

## 📁 Repository Structure

```text
BankSphere/
├── backend/                        # Spring Boot Microservices (Spring Boot 4.1.0, Java 21)
│   ├── api-gateway/                # API Gateway (Spring Cloud Gateway MVC, port 8080)
│   ├── customer-service/           # Customer, Auth & Beneficiary management (port 8081)
│   ├── account-service/            # Account management & balance ledger (port 8082)
│   └── transaction-service/        # Saga orchestration & transaction history (port 8083)
├── database/                       # Database notes and scripts
├── docs/                           # Architecture and performance documentation
│   ├── ARCHITECTURE.md
│   └── PERFORMANCE.md
├── performance/                    # k6 performance benchmark suites
│   ├── scripts/
│   │   ├── helpers/auth.js
│   │   ├── 01_baseline.js
│   │   ├── 02_load_test.js
│   │   ├── 03_stress_test.js
│   │   └── 04_contention_test.js
│   ├── results/
│   │   └── .gitkeep
│   └── README.md
├── .gitignore
├── LICENSE
└── README.md
```

---

## 🏛️ Core Architecture & Engineering Highlights

* **Microservices & API Gateway**: Centralized Spring Cloud Gateway MVC entry point on port `8080` routing to downstream `customer-service` (8081), `account-service` (8082), and `transaction-service` (8083).
* **Optimistic Concurrency Control (OCC)**: JPA `@Version` on `Account` with an automated 3-attempt synchronized retry loop and randomized exponential jitter backoff in `AccountService` to handle high-concurrency balance updates without data corruption.
* **Resilience4j Circuit Breaker & Retry**: Configured on `AccountServiceClient` for inter-service calls with custom exception taxonomy (`AccountServiceTimeoutException`, `AccountServiceRejectedException`, `AccountServiceSecurityException`, `AccountServiceUnavailableException`) and dedicated fallback handlers.
* **Idempotency**: Dual-layer idempotency using `Idempotency-Key` headers for client transaction requests and unique `operation_key` ledger tracking in `balance_operations` for exact-once balance modifications.
* **Saga Pattern & Distributed Compensation**: Orchestrated multi-step Transfer Sagas (Source Debit $\rightarrow$ Target Credit) with automated compensation refunds (`{txId}-DEBIT-COMPENSATION`) on credit failures and same-key reconciliation for ambiguous timeout states (`FAILED_NEEDS_MANUAL_REVIEW`).
* **Security, RBAC & BOLA Prevention**:
  * BCrypt password hashing (`passwordHash` never returned in API responses; safe DTO projection via `CustomerResponseDto`).
  * HMAC-SHA256 JWT validation across services with `CUSTOMER`, `ADMIN`, and `SERVICE` role-based access control.
  * Broken Object Level Authorization (BOLA) enforcement verifying account ownership before withdrawals and transfers.
  * Internal `SERVICE` token generation and caching for secure inter-service balance updates.
* **Observability & Distributed Tracing**:
  * Request correlation IDs (`X-Correlation-ID`) generated/forwarded by the Gateway, bound to SLF4J/Logback MDC across threads, and propagated via `RestClient` outbound interceptors.
  * Micrometer and Spring Boot Actuator metrics (`/actuator/prometheus`) tracking login, registration, account creation, balance update, and transaction duration counters and timers.
* **Integration Testing & Hardening**: Testcontainers-backed PostgreSQL integration test suites for real-database OCC, idempotency, and Saga failure-mode verification.
* **Performance & Capacity Benchmarking**: Live k6 benchmark suites covering baseline single-thread, distributed multi-user load, saturation stress, and single-account OCC contention.

---

## 📊 Measured Benchmark Results (Phase 8 Summary)

All benchmarks executed live against the API Gateway (`http://localhost:8080`) backed by PostgreSQL 17:

| Scenario | Concurrency | Total Requests | Throughput | Avg Latency | p50 Latency | p95 Latency | HTTP Errors |
|---|---|---|---|---|---|---|---|
| **01 - Baseline** | 1 VU (30s) | 688 | **21.80 req/s** | 25.79 ms | 26.65 ms | 47.44 ms | **0.00%** |
| **02 - Distributed Load** | 20 VUs (2m 15s) | 8,836 | **63.56 req/s** | 98.93 ms | 44.33 ms | 352.95 ms | **0.00%** |
| **03 - Saturation Stress** | 100 VUs (2m 30s) | 5,682 | **36.55 req/s** | 1,438.80 ms | 1,172.10 ms | 3,352.65 ms | **0.00%** |
| **04 - OCC Contention** | 10 VUs (30s Hot Acc) | 581 | **18.79 req/s** | 499.98 ms | 479.55 ms | 991.34 ms | **0.00%** |

*For the complete detailed report and custom metrics, see [docs/PERFORMANCE.md](docs/PERFORMANCE.md).*

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Language & Framework** | Java 21, Spring Boot 4.1.0, Spring Cloud Gateway MVC |
| **Data & Persistence** | PostgreSQL 17, Spring Data JPA, Hibernate 7, HikariCP |
| **Security & Auth** | Spring Security 7, OAuth2 Resource Server, Nimbus Jose JWT, BCrypt |
| **Resilience & Observability**| Resilience4j 2.x, Micrometer, Actuator, SLF4J / Logback MDC |
| **Testing & Benchmarks** | JUnit 5, Mockito, WireMock, Testcontainers PostgreSQL, Grafana k6 v2.2.0 |
| **Build & Tooling** | Maven 3.9+, Maven Wrapper |

---

## 🚀 Quick Start (Local Run)

### 1. Prerequisites
- Java 21 JDK
- PostgreSQL 17 running locally on port `5432` with database `banking_db` created
- (Optional) Grafana k6 for performance benchmarking

### 2. Configure Environment & Start Services
Set the `JWT_SECRET` environment variable (minimum 32 characters) and start each microservice:

```powershell
# In Terminal 1 (Customer Service - Port 8081):
$env:JWT_SECRET = "<your-local-jwt-secret>"
cd backend/customer-service
.\mvnw.cmd spring-boot:run

# In Terminal 2 (Account Service - Port 8082):
$env:JWT_SECRET = "<your-local-jwt-secret>"
cd backend/account-service
.\mvnw.cmd spring-boot:run

# In Terminal 3 (Transaction Service - Port 8083):
$env:JWT_SECRET = "<your-local-jwt-secret>"
cd backend/transaction-service
.\mvnw.cmd spring-boot:run

# In Terminal 4 (API Gateway - Port 8080):
cd backend/api-gateway
.\mvnw.cmd spring-boot:run
```

---

## 🧪 Running Tests & Benchmarks

### Unit & Integration Test Suites
```powershell
cd backend/customer-service && .\mvnw.cmd test
cd backend/account-service && .\mvnw.cmd test
cd backend/transaction-service && .\mvnw.cmd test
cd backend/api-gateway && .\mvnw.cmd test
```

### k6 Performance Benchmarks
See [performance/README.md](performance/README.md) for instructions to run baseline, load, stress, and contention benchmarks.

---

## 📜 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

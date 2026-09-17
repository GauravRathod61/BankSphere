# BankSphere Performance & Benchmarking Report (Phase 8)

## 1. Executive Summary

This document reports the actual measured performance, capacity, and concurrency characteristics of the BankSphere microservices platform. All benchmarks were executed using **Grafana k6 v2.2.0** targeting the **API Gateway (port 8080)** with requests routed across all downstream services (`customer-service`, `account-service`, `transaction-service`) backed by PostgreSQL.

---

## 2. Test Environment

- **Operating System**: Windows 11 x64
- **Runtime**: Java 21 / Spring Boot 4.1.0
- **Database**: PostgreSQL 17 (local instance, port 5432)
- **API Gateway**: Spring Cloud Gateway MVC (port 8080)
- **Downstream Services**:
  - `customer-service` (port 8081)
  - `account-service` (port 8082)
  - `transaction-service` (port 8083)
- **Auth**: API Gateway forwards incoming `Authorization: Bearer <token>` headers; downstream protected services (`customer-service`, `account-service`, `transaction-service`) perform Spring Security HMAC-SHA256 JWT validation and RBAC/BOLA authorization
- **Resilience**: Resilience4j CircuitBreaker + Retry with exponential jitter backoff
- **Concurrency Control**: JPA Optimistic Locking (`@Version`) with 3-attempt synchronized retry loop

---

## 3. Actual Benchmark Results Summary

| Metric | 01 - Baseline (1 VU) | 02 - Distributed Load (20 VUs) | 03 - Saturation Stress (100 VUs) | 04 - OCC Contention (10 VUs Hot Acc) |
|---|---|---|---|---|
| **Status** | ✅ PASSED (100%) | ✅ PASSED (99.97%) | ✅ PASSED (100%) | ✅ PASSED (100%) |
| **Duration** | 30s | 2m 15s | 2m 30s | 30s |
| **Peak VUs** | 1 | 20 | 100 | 10 |
| **Total HTTP Requests** | 688 | 8,836 | 5,682 | 581 |
| **Total Iterations** | 136 | 8,756 | 5,562 | 572 |
| **Throughput (RPS)** | **21.80 req/s** | **63.56 req/s** | **36.55 req/s** | **18.79 req/s** |
| **Average Latency** | 25.79 ms | 98.93 ms | 1,438.80 ms | 499.98 ms |
| **Median (p50) Latency** | 26.65 ms | 44.33 ms | 1,172.10 ms | 479.55 ms |
| **90th Percentile (p90)** | 45.91 ms | 267.70 ms | 3,000.00 ms | 883.79 ms |
| **95th Percentile (p95)** | 47.44 ms | 352.95 ms | 3,352.65 ms | 991.34 ms |
| **Max Latency** | 1,126.03 ms | 1,540.88 ms | 5,204.00 ms | 2,509.00 ms |
| **HTTP Error Rate** | **0.00%** (0 / 688) | **0.00%** (0 / 8,836) | **0.00%** (0 / 5,682) | **0.00%** (0 / 581) |
| **Checks Success Rate** | **100.00%** (1232/1232) | **99.97%** (17588/17592) | **100.00%** (120/120) | **100.00%** (580/580) |

---

## 4. Scenario Breakdown & Custom Metrics

### Scenario 1: Baseline (`01_baseline.js`)
- Single-thread execution of complete user workflows (deposit, withdraw, transfer, mini-statement, account balance query).
- **Checks passed**: 1,232 / 1,232 (100.00%)
- **p95 Latency**: 47.44 ms
- **Average request duration**: 25.79 ms

### Scenario 2: Distributed Load (`02_load_test.js`)
- Distributed pool of 20 customer accounts under multi-stage ramp-up (0 → 10 → 20 VUs).
- **Successful Transactions**: 5,607
- **Failed Transactions**: 4 (0.07% under peak concurrency due to occasional random source/target account overlap)
- **Transfer Duration (Saga debit + credit)**: avg=175.49 ms, med=105.00 ms, p95=476.00 ms
- **Deposit Duration (Single-account OCC)**: avg=91.30 ms, med=48.00 ms, p95=266.20 ms

### Scenario 3: Saturation & Stress (`03_stress_test.js`)
- Staged ramp up to 100 concurrent VUs over 2.5 minutes across 30 pre-provisioned accounts.
- **Total Transactions**: 3,963
- **HTTP Error Rate**: 0.00% (Zero dropped requests or unhandled 5xx exceptions)
- **Transfer Duration**: avg=1,657.03 ms, med=1,393.00 ms, p95=3,711.20 ms
- **System Behavior**: At 100 VUs, the system saturated gracefully with queueing; p95 latency rose to 3.35s, with 0 connection drops.

### Scenario 4: Single-Account OCC Contention (`04_contention_test.js`)
- 10 constant concurrent VUs simultaneously executing deposits and transfers against the exact same Hot Account (`496418970A`).
- **Total Successful Contention Transactions**: 572
- **OCC Conflicts Handled**: 0 hard failures (100% absorbed by the 3-attempt backoff retry loop)
- **Hot Account Initial Balance**: 1,000,000.00
- **Hot Account Final Balance**: 999,830.00
- **Hot Account Final JPA Version**: `426` (verified incremented 426 times concurrently)
- **Financial Consistency**: 100% exact mathematical balance match; zero data loss or double-counting.

---

## 5. Execution Commands & Raw Data Artifacts

All raw benchmark JSON files are generated and stored under `performance/results/`:

```powershell
# 1. Baseline Benchmark
k6 run --summary-export performance/results/baseline_summary.json performance/scripts/01_baseline.js

# 2. Distributed Load Benchmark
k6 run --summary-export performance/results/load_summary.json performance/scripts/02_load_test.js

# 3. Saturation Stress Benchmark
k6 run --summary-export performance/results/stress_summary.json performance/scripts/03_stress_test.js

# 4. OCC Contention Benchmark
k6 run --summary-export performance/results/contention_summary.json performance/scripts/04_contention_test.js
```

### Raw Summary Files
- `performance/results/baseline_summary.json` (688 requests)
- `performance/results/load_summary.json` (8,836 requests)
- `performance/results/stress_summary.json` (5,682 requests)
- `performance/results/contention_summary.json` (581 requests)

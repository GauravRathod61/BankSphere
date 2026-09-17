# BankSphere Performance & Benchmarking Suite (k6)

This directory contains the performance, capacity, and concurrency benchmark suite for the BankSphere banking platform using [k6](https://k6.io/).

---

## 1. Directory Structure

```
performance/
├── README.md                      # Setup and execution guide
├── scripts/
│   ├── helpers/
│   │   └── auth.js                # Customer registration, JWT login & account provisioning helper
│   ├── 01_baseline.js             # Single VU smoke/baseline benchmark
│   ├── 02_load_test.js            # Distributed load benchmark across account pool (1-20 VUs)
│   ├── 03_stress_test.js          # Ramp-up stress benchmark to find saturation point (1-100 VUs)
│   └── 04_contention_test.js      # Deliberate single-account OCC contention & race condition test
└── results/
    └── .gitkeep                   # Local raw test results directory (git-ignored)
```

---

## 2. Prerequisites & Installation

### Install k6 on Windows
```powershell
winget install --id GrafanaLabs.k6 --accept-source-agreements --accept-package-agreements
```

Verify installation:
```powershell
k6 version
```

---

## 3. Starting the Services

All 4 microservices must be running and connected to PostgreSQL (`banking_db`) with a shared `JWT_SECRET`:

### In Terminal 1 (Customer Service - Port 8081):
```powershell
$env:JWT_SECRET = "supersecretjwtkeywith32charactersormore!"
cd backend/customer-service
.\mvnw.cmd spring-boot:run
```

### In Terminal 2 (Account Service - Port 8082):
```powershell
$env:JWT_SECRET = "supersecretjwtkeywith32charactersormore!"
cd backend/account-service
.\mvnw.cmd spring-boot:run
```

### In Terminal 3 (Transaction Service - Port 8083):
```powershell
$env:JWT_SECRET = "supersecretjwtkeywith32charactersormore!"
cd backend/transaction-service
.\mvnw.cmd spring-boot:run
```

### In Terminal 4 (API Gateway - Port 8080):
```powershell
cd backend/api-gateway
.\mvnw.cmd spring-boot:run
```

---

## 4. Running the Benchmark Suites

All tests route through the API Gateway on `http://localhost:8080`.

### 1. Baseline Test (1 VU, 30s)
Establishes clean single-thread latency and throughput baselines:
```powershell
k6 run --summary-export performance/results/baseline_summary.json performance/scripts/01_baseline.js
```

### 2. Distributed Load Test (1-20 VUs, ~2 min)
Tests normal distributed banking load across a pool of 20 accounts:
```powershell
k6 run --summary-export performance/results/load_summary.json performance/scripts/02_load_test.js
```

### 3. Stress & Saturation Test (1-100 VUs, ~2.5 min)
Ramps up to 100 concurrent VUs to determine system breaking point and latency degradation curves:
```powershell
k6 run --summary-export performance/results/stress_summary.json performance/scripts/03_stress_test.js
```

### 4. Single-Account OCC Contention Test (10 concurrent VUs, 30s)
Deliberately bombards the *exact same account* with simultaneous concurrent deposits and transfers to test Optimistic Concurrency Control (`@Version`), lock retries with jitter backoff, and financial balance consistency:
```powershell
k6 run --summary-export performance/results/contention_summary.json performance/scripts/04_contention_test.js
```

---

## 5. Metrics Measured & Analyzed

- **Throughput (RPS)**: `http_reqs` rate per second across all endpoints.
- **Latency Distribution**:
  - `avg`: Arithmetic mean request duration
  - `p(50)`: 50th percentile (median)
  - `p(95)`: 95th percentile
  - `p(99)`: 99th percentile tail latency
- **Error Rate**: Percentage of failed HTTP requests or unexpected status codes.
- **Saga / Balance Update Latency**: Multi-step transaction processing duration including inter-service calls.
- **OCC Conflicts**: Retried vs rejected balance updates under race conditions.

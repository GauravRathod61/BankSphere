# BankSphere Backend

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=java" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.x-green?style=flat-square&logo=spring-boot" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/PostgreSQL-17-blue?style=flat-square&logo=postgresql" alt="PostgreSQL" />
</p>

**BankSphere** is a digital banking backend built with a Spring Boot microservices architecture: an API Gateway routing to independent Customer, Account, and Transaction services.

---

## 📁 Repository Structure

```text
banksphere/
├── backend/                        # Spring Boot Microservices
│   ├── api-gateway/                # API Gateway (Spring Cloud Gateway, port 8080)
│   ├── customer-service/           # Customer & Beneficiary management (port 8081)
│   ├── account-service/            # Account management (port 8082)
│   └── transaction-service/        # Transactions & Statements (port 8083)
├── database/                       # Database schema & migration notes
├── docs/                           # Architecture & API documentation
│   └── ARCHITECTURE.md
├── .gitignore
├── LICENSE
└── README.md
```

---

## ✨ Key Features

* **Customer & Account Management** — Create and manage customers. Open Savings and Current accounts.
* **Beneficiary Management** — Add and remove beneficiaries linked to customer profiles.
* **Transactions** — Deposits, Withdrawals, and Fund Transfers with balance validation.
* **Statements** — Mini Statement (last 10 transactions) and Monthly Statement (filtered by year/month).
* **Account Controls** — Freeze and unfreeze accounts.
* **Minimum Balance Enforcement** — Prevents withdrawals below the required minimum for Savings accounts.

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 3.x, Spring Data JPA, Spring Cloud Gateway, Maven |
| **Database** | PostgreSQL |

---

## 🚀 Quick Start (Local, without Docker)

Each service is an independent Maven project. Start Postgres locally, then run each service from its own directory:

```bash
# Ensure PostgreSQL is running locally and a `banking_db` database exists

cd backend/customer-service
./mvnw spring-boot:run   # port 8081

cd backend/account-service
./mvnw spring-boot:run   # port 8082

cd backend/transaction-service
./mvnw spring-boot:run   # port 8083

cd backend/api-gateway
./mvnw spring-boot:run   # port 8080 — single entry point routing to the above
```

Datasource URL, username, and password for each service are configured in that service's `src/main/resources/application.yml`. Update these to match your local Postgres setup, or externalize them via environment variables before deploying anywhere beyond local development.

For detailed architecture and API documentation, see **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

---

## 📜 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

# BankSphere Architecture

The system is split into distinct microservices representing bounded contexts in a banking domain, communicating over HTTP behind a single API Gateway entry point.

## 🏛️ Components

1. **API Gateway (Port 8080)**: Central entry point built on Spring Cloud Gateway. Routes `/customers/**` to Customer Service, `/accounts/**` to Account Service, and `/transactions/**` to Transaction Service.
2. **Customer Service (Port 8081)**: Manages customer profiles, demographics, and beneficiary mapping.
3. **Account Service (Port 8082)**: Manages financial accounts (Savings/Current), enforces minimum balance rules, and provides freeze/unfreeze mechanisms.
4. **Transaction Service (Port 8083)**: Handles financial transactions (Deposits, Withdrawals, Transfers).
5. **Database Layer**: PostgreSQL handles relational data persistence.

## 🔌 API Documentation (Examples)

Endpoints below can be called directly against each service's own port, or through the API Gateway at `http://localhost:8080`.

### Customers
* `POST /customers` - Create a new customer
* `GET /customers/{id}` - Get a customer
* `GET /customers` - List all customers

### Beneficiaries
* `POST /customers/{id}/beneficiaries` - Add a beneficiary
* `GET /customers/{id}/beneficiaries` - Get customer beneficiaries
* `DELETE /customers/{customerId}/beneficiaries/{benId}` - Delete a beneficiary

### Accounts
* `POST /accounts` - Open a new account
* `GET /accounts/customer/{id}` - Get accounts by customer
* `POST /accounts/{accountNumber}/freeze` - Freeze account
* `POST /accounts/{accountNumber}/update-balance` - Internal microservice endpoint for balance updates (called by Transaction Service)

### Transactions & Statements
* `POST /transactions` - Process a Deposit, Withdraw, or Transfer
* `GET /transactions/account/{accountNumber}` - Get paginated transaction history
* `GET /transactions/mini-statement/{accountNumber}` - Get last 10 transactions
* `GET /transactions/monthly-statement/{accountNumber}?year=YYYY&month=M` - Get statement for a calendar month

# Database Infrastructure

This directory is reserved for database initialization scripts, schema migrations (e.g., Flyway/Liquibase), and database documentation.

Currently, the PostgreSQL database is initialized dynamically via Spring Boot's Hibernate auto-DDL configuration during local development. Each service's datasource connection is configured in its own `src/main/resources/application.yml`.

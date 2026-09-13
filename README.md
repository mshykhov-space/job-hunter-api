# Job Hunter API

Kotlin/Spring Boot API for [Job Hunter](https://github.com/mshykhov/job-hunter). It accepts normalized vacancy data, persists and matches jobs, and exposes public browsing plus authenticated job-management endpoints.

## Run locally

Requires JDK 21 and Docker.

```sh
docker compose up -d
OIDC_ENABLED=false ./gradlew bootRun --args='--spring.profiles.active=local --server.address=127.0.0.1'
```

This command disables authentication for local development and binds the API to loopback. The local profile listens on `http://localhost:8095`. Swagger UI is available at `/swagger-ui`, and the OpenAPI document at `/api-docs`.

## Architecture

Controllers form the HTTP boundary, application services own vacancy and preference rules, and infrastructure adapters provide persistence, security, AI providers, and observability. PostgreSQL is the source of truth; Flyway manages schema changes.

The API also contains authenticated contracts for a separately configured automation runner. Those endpoints keep runner leases and recovery state in the API; they do not grant browser access or application submission.

## Configuration

`docker-compose.yml` starts PostgreSQL on port 5440 with the local profile's database defaults. The example `.env` is a reference; `bootRun` does not load it automatically. Export settings into the process environment when overriding configuration. Set `OIDC_ENABLED=true` and provide `OIDC_ISSUERS` and `OIDC_AUDIENCE` when testing an OIDC-protected deployment. AI and material-encryption settings are optional unless their corresponding features are enabled.

See the [documentation map](docs/README.md) for feature contracts and operational guides.

## Verify

```sh
./gradlew test
```

## License

[MIT](LICENSE)

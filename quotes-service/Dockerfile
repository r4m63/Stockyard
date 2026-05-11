# syntax=docker/dockerfile:1
#
# Stockyard quotes-service — multi-stage build.
# - Stage 1 (builder) compiles a static binary against Go 1.22 Alpine.
# - Stage 2 ships only the binary on distroless/static.
#
# The runtime image expects /dev/stockyard to be bind-mounted from the
# host (the kernel module is loaded outside Docker — see
# driver/scripts/load.sh and driver/README.md).

FROM golang:1.22-alpine AS builder

WORKDIR /src

# Cache deps before copying the rest of the source.
# Copy go.sum too so `go mod download` verifies against the locked
# checksums — without it a compromised module proxy could ship a
# substituted dependency unnoticed.
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go mod download

COPY . .

RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux \
    go build -trimpath -ldflags="-s -w" -o /out/quotes ./cmd/quotes && \
    CGO_ENABLED=0 GOOS=linux \
    go build -trimpath -ldflags="-s -w" -o /out/healthcheck ./cmd/healthcheck

# ----------------------------------------------------------------------

FROM gcr.io/distroless/static-debian12:nonroot

WORKDIR /
COPY --from=builder /out/quotes /quotes
COPY --from=builder /out/healthcheck /healthcheck

USER nonroot:nonroot

ENV STOCKYARD_DRIVER_PATH=/dev/stockyard \
    STOCKYARD_REDIS_ADDR=redis:6379 \
    STOCKYARD_CH_DSN=clickhouse://default@clickhouse:9000/default \
    STOCKYARD_HEALTH_PORT=8080 \
    STOCKYARD_CH_BATCH_SIZE=1000 \
    STOCKYARD_CH_BATCH_MS=1000

EXPOSE 8080

ENTRYPOINT ["/quotes"]

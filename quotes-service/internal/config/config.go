// Package config gathers all environment-driven settings into one
// struct.  Every knob has a sensible default so the service runs
// out-of-the-box in dev (against a local Redis + ClickHouse + mock
// driver).  Production deployments override via envvars.
package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config is the immutable settings snapshot taken at process start.
type Config struct {
	DriverPath string

	RedisAddr     string
	RedisPassword string
	RedisDB       int

	ClickHouseDSN string

	HealthPort int

	CHBatchSize int
	CHBatchMS   time.Duration

	// MetricsServiceName feeds OTel resource attribute service.name.
	MetricsServiceName string
}

// Load reads the environment.  Validation is minimal — we trust ops
// to set sane values; the service will fail fast at first connection
// attempt if Redis/CH are unreachable.
func Load() (Config, error) {
	cfg := Config{
		DriverPath:         getenv("STOCKYARD_DRIVER_PATH", "/dev/stockyard"),
		RedisAddr:          getenv("STOCKYARD_REDIS_ADDR", "127.0.0.1:6379"),
		RedisPassword:      os.Getenv("STOCKYARD_REDIS_PASSWORD"),
		ClickHouseDSN:      getenv("STOCKYARD_CH_DSN", "clickhouse://default@127.0.0.1:9000/default"),
		MetricsServiceName: getenv("STOCKYARD_SERVICE_NAME", "quotes-service"),
	}

	db, err := getenvInt("STOCKYARD_REDIS_DB", 0)
	if err != nil {
		return cfg, err
	}
	cfg.RedisDB = db

	port, err := getenvInt("STOCKYARD_HEALTH_PORT", 8080)
	if err != nil {
		return cfg, err
	}
	cfg.HealthPort = port

	batchSize, err := getenvInt("STOCKYARD_CH_BATCH_SIZE", 1000)
	if err != nil {
		return cfg, err
	}
	if batchSize < 1 {
		return cfg, fmt.Errorf("STOCKYARD_CH_BATCH_SIZE must be >= 1, got %d", batchSize)
	}
	cfg.CHBatchSize = batchSize

	batchMS, err := getenvInt("STOCKYARD_CH_BATCH_MS", 1000)
	if err != nil {
		return cfg, err
	}
	if batchMS < 1 {
		return cfg, fmt.Errorf("STOCKYARD_CH_BATCH_MS must be >= 1, got %d", batchMS)
	}
	cfg.CHBatchMS = time.Duration(batchMS) * time.Millisecond

	return cfg, nil
}

func getenv(key, def string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return def
}

func getenvInt(key string, def int) (int, error) {
	// LookupEnv returns ok=true for variables set to the empty string
	// (e.g. `STOCKYARD_HEALTH_PORT=` in a k8s ConfigMap).  Treat empty
	// the same as unset so the service starts with the documented
	// default rather than crashing on strconv.Atoi("").
	raw, ok := os.LookupEnv(key)
	if !ok || raw == "" {
		return def, nil
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", key, err)
	}
	return v, nil
}

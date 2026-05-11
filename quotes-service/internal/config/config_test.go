package config

import (
	"os"
	"testing"
	"time"
)

func TestLoad_Defaults(t *testing.T) {
	// Clear known envvars to force defaults.  t.Setenv("", "") sets the
	// var to an empty string rather than unsetting it, which would trip
	// strconv.Atoi.  We Unsetenv with a saved-value restore on cleanup.
	for _, k := range []string{
		"STOCKYARD_DRIVER_PATH", "STOCKYARD_REDIS_ADDR", "STOCKYARD_REDIS_DB",
		"STOCKYARD_CH_DSN", "STOCKYARD_HEALTH_PORT",
		"STOCKYARD_CH_BATCH_SIZE", "STOCKYARD_CH_BATCH_MS",
		"STOCKYARD_SERVICE_NAME", "STOCKYARD_REDIS_PASSWORD",
	} {
		k := k
		prev, had := os.LookupEnv(k)
		_ = os.Unsetenv(k)
		t.Cleanup(func() {
			if had {
				_ = os.Setenv(k, prev)
			} else {
				_ = os.Unsetenv(k)
			}
		})
	}

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	checks := map[string]any{
		"DriverPath":         "/dev/stockyard",
		"RedisAddr":          "127.0.0.1:6379",
		"RedisDB":            0,
		"ClickHouseDSN":      "clickhouse://default@127.0.0.1:9000/default",
		"HealthPort":         8080,
		"CHBatchSize":        1000,
		"CHBatchMS":          1000 * time.Millisecond,
		"MetricsServiceName": "quotes-service",
	}

	if cfg.DriverPath != checks["DriverPath"] {
		t.Errorf("DriverPath = %q, want %q", cfg.DriverPath, checks["DriverPath"])
	}
	if cfg.RedisAddr != checks["RedisAddr"] {
		t.Errorf("RedisAddr = %q, want %q", cfg.RedisAddr, checks["RedisAddr"])
	}
	if cfg.HealthPort != checks["HealthPort"] {
		t.Errorf("HealthPort = %d, want %d", cfg.HealthPort, checks["HealthPort"])
	}
	if cfg.CHBatchSize != checks["CHBatchSize"] {
		t.Errorf("CHBatchSize = %d, want %d", cfg.CHBatchSize, checks["CHBatchSize"])
	}
	if cfg.CHBatchMS != checks["CHBatchMS"] {
		t.Errorf("CHBatchMS = %s, want %s", cfg.CHBatchMS, checks["CHBatchMS"])
	}
}

func TestLoad_Override(t *testing.T) {
	t.Setenv("STOCKYARD_DRIVER_PATH", "/tmp/fake-driver")
	t.Setenv("STOCKYARD_REDIS_ADDR", "redis.prod:6380")
	t.Setenv("STOCKYARD_HEALTH_PORT", "9090")
	t.Setenv("STOCKYARD_CH_BATCH_SIZE", "500")
	t.Setenv("STOCKYARD_CH_BATCH_MS", "250")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.DriverPath != "/tmp/fake-driver" {
		t.Errorf("DriverPath = %q", cfg.DriverPath)
	}
	if cfg.RedisAddr != "redis.prod:6380" {
		t.Errorf("RedisAddr = %q", cfg.RedisAddr)
	}
	if cfg.HealthPort != 9090 {
		t.Errorf("HealthPort = %d", cfg.HealthPort)
	}
	if cfg.CHBatchSize != 500 {
		t.Errorf("CHBatchSize = %d", cfg.CHBatchSize)
	}
	if cfg.CHBatchMS != 250*time.Millisecond {
		t.Errorf("CHBatchMS = %s", cfg.CHBatchMS)
	}
}

func TestLoad_BadIntRejected(t *testing.T) {
	t.Setenv("STOCKYARD_HEALTH_PORT", "not-a-number")
	if _, err := Load(); err == nil {
		t.Error("Load: want error for non-int STOCKYARD_HEALTH_PORT, got nil")
	}
}

func TestLoad_BatchSizeRange(t *testing.T) {
	t.Setenv("STOCKYARD_CH_BATCH_SIZE", "0")
	if _, err := Load(); err == nil {
		t.Error("Load: want error for batch size 0")
	}
}

// TestLoad_EmptyEnvvar is the M1 regression — catches a future
// revert of `getenvInt`'s empty-string check.  Kubernetes ConfigMaps
// produce `KEY=` entries which LookupEnv reports as set-with-empty;
// the loader must treat that the same as unset, not crash on Atoi("").
func TestLoad_EmptyEnvvar(t *testing.T) {
	t.Setenv("STOCKYARD_HEALTH_PORT", "")
	t.Setenv("STOCKYARD_CH_BATCH_SIZE", "")
	t.Setenv("STOCKYARD_CH_BATCH_MS", "")
	t.Setenv("STOCKYARD_REDIS_DB", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load with empty envvars: %v (regression of M1)", err)
	}
	if cfg.HealthPort != 8080 {
		t.Errorf("HealthPort = %d, want 8080 default", cfg.HealthPort)
	}
	if cfg.CHBatchSize != 1000 {
		t.Errorf("CHBatchSize = %d, want 1000 default", cfg.CHBatchSize)
	}
	if cfg.RedisDB != 0 {
		t.Errorf("RedisDB = %d, want 0 default", cfg.RedisDB)
	}
}

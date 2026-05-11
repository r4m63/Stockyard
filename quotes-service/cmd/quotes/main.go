// Package main is the entry point of stockyard quotes-service.
//
// Pipeline:
//
//	/dev/stockyard ──▶ driver.Reader ──▶ ticks chan
//	                                       │
//	                              pipeline.Fanout
//	                                ├─▶ redis chan ──▶ sinks.RedisSink
//	                                └─▶    ch chan ──▶ sinks.ClickHouseSink
//
// Cross-cutting:
//
//	health.Server  — /healthz, /readyz, /metrics on HEALTH_PORT
//	telemetry      — Prometheus counters wired from the atomic fields
//	                 of the reader / fanout / sinks
//
// Graceful shutdown order on SIGTERM/SIGINT:
//
//	1. Cancel root ctx.
//	2. driver.Reader stops reading, closes fd.
//	3. ticks chan drains; pipeline.Fanout closes redis/ch channels.
//	4. Redis sink drains its channel and exits.
//	5. ClickHouse sink flushes its in-flight batch and exits.
//	6. Health server stops accepting requests.
//
// Each step has a small timeout so a broken sink can't hold up the
// whole process on shutdown.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/ClickHouse/clickhouse-go/v2"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/redis/go-redis/v9"

	"github.com/stockyard/quotes-service/internal/config"
	"github.com/stockyard/quotes-service/internal/driver"
	"github.com/stockyard/quotes-service/internal/health"
	"github.com/stockyard/quotes-service/internal/pipeline"
	"github.com/stockyard/quotes-service/internal/sinks"
	"github.com/stockyard/quotes-service/internal/telemetry"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))

	if err := run(log); err != nil {
		log.Error("quotes-service exited with error", slog.Any("err", err))
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	log.Info("config loaded",
		slog.String("driver_path", cfg.DriverPath),
		slog.String("redis_addr", cfg.RedisAddr),
		slog.Int("health_port", cfg.HealthPort),
		slog.Int("ch_batch_size", cfg.CHBatchSize),
		slog.Duration("ch_batch_interval", cfg.CHBatchMS))

	rootCtx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	// ----- Clients --------------------------------------------------
	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
	})
	defer rdb.Close()

	chOpts, err := clickhouse.ParseDSN(cfg.ClickHouseDSN)
	if err != nil {
		return fmt.Errorf("clickhouse dsn: %w", err)
	}
	chConn, err := clickhouse.Open(chOpts)
	if err != nil {
		return fmt.Errorf("clickhouse open: %w", err)
	}
	defer chConn.Close()

	// ----- Channels -------------------------------------------------
	// Driver → Fanout: 4096 fits ~80 ms at 50 Hz × 1000 tickers.
	ticks := make(chan pipeline.Tick, 4096)
	fanout := pipeline.NewFanout(ticks, 256, 4096)

	// ----- Shared metric counters ----------------------------------
	var (
		ticksTotal    atomic.Uint64
		driverReopens atomic.Uint64
	)

	// ----- Sinks ----------------------------------------------------
	redisSink := &sinks.RedisSink{
		Client: rdb,
		In:     fanout.Redis,
		Log:    log.With(slog.String("component", "redis-sink")),
	}
	chSink := &sinks.ClickHouseSink{
		Conn:     chConn,
		In:       fanout.CH,
		Size:     cfg.CHBatchSize,
		Interval: cfg.CHBatchMS,
		Log:      log.With(slog.String("component", "ch-sink")),
	}

	// ----- Health + metrics ----------------------------------------
	lastTick := &health.LastTickAt{}
	metrics := &telemetry.Metrics{
		TicksTotal:         &ticksTotal,
		TicksDroppedRedis:  &fanout.DroppedRedis,
		TicksDroppedCH:     &fanout.DroppedCH,
		RedisPublishErrors: &redisSink.PublishErrors,
		CHBatchErrors:      &chSink.BatchErrors,
		CHRowsInserted:     &chSink.RowsInserted,
		CHDropped:          &chSink.Dropped,
		DriverReopens:      &driverReopens,
	}
	if err := metrics.Register(prometheus.DefaultRegisterer); err != nil {
		return fmt.Errorf("metrics register: %w", err)
	}

	healthSrv := &health.Server{
		Addr:        fmt.Sprintf(":%d", cfg.HealthPort),
		Redis:       rdb,
		CH:          chConn,
		LastTick:    lastTick,
		LivenessMax: 5 * time.Second,
		Log:         log.With(slog.String("component", "health")),
	}

	// ----- Driver reader (wrapped to count ticks + reopens) --------
	driverOut := make(chan pipeline.Tick, 256)
	reader := &driver.Reader{
		Path: cfg.DriverPath,
		Out:  driverOut,
		Log:  log.With(slog.String("component", "driver")),
	}

	// ----- Spin everything up --------------------------------------
	var wg sync.WaitGroup

	// Wrapper that forwards driverOut → ticks while bumping counters
	// and the liveness heartbeat.  Lets us keep driver/reader free of
	// metric coupling.
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer close(ticks)
		for {
			select {
			case <-rootCtx.Done():
				return
			case t, ok := <-driverOut:
				if !ok {
					return
				}
				ticksTotal.Add(1)
				lastTick.Touch()
				select {
				case <-rootCtx.Done():
					return
				case ticks <- t:
				}
			}
		}
	}()

	// The driverReopens counter is reachable from the metrics block
	// above; the reader itself doesn't bump it yet — TASK-011 wires a
	// reopen callback once we have telemetry plumbing.  For MVP the
	// metric reads 0 until then.
	wg.Add(1)
	go func() {
		defer wg.Done()
		err := reader.Run(rootCtx)
		if err != nil && !errors.Is(err, context.Canceled) {
			log.Error("driver reader exited", slog.Any("err", err))
		}
		close(driverOut)
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		fanout.Run(rootCtx)
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := redisSink.Run(rootCtx); err != nil {
			log.Error("redis sink exited", slog.Any("err", err))
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := chSink.Run(rootCtx); err != nil {
			log.Error("clickhouse sink exited", slog.Any("err", err))
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := healthSrv.Run(rootCtx); err != nil {
			log.Error("health server exited", slog.Any("err", err))
		}
	}()

	log.Info("quotes-service running",
		slog.String("driver", cfg.DriverPath),
		slog.String("redis", cfg.RedisAddr),
		slog.Int("health_port", cfg.HealthPort))

	wg.Wait()
	log.Info("quotes-service stopped cleanly",
		slog.Uint64("ticks_total", ticksTotal.Load()),
		slog.Uint64("redis_dropped", fanout.DroppedRedis.Load()),
		slog.Uint64("redis_publish_errors", redisSink.PublishErrors.Load()),
		slog.Uint64("ch_rows_inserted", chSink.RowsInserted.Load()),
		slog.Uint64("ch_batch_errors", chSink.BatchErrors.Load()))
	return nil
}

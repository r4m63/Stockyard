// Package telemetry registers Prometheus collectors and wires the
// service-level counters used across sinks.  Tracing via OTLP is
// stubbed out in MVP — TASK-011 turns it on once the docker-compose
// stack includes an OTel collector.
package telemetry

import (
	"sync/atomic"

	"github.com/prometheus/client_golang/prometheus"
)

// Metrics owns every Prometheus collector.  Sinks read their atomic
// counters directly; the collectors below expose them via /metrics.
type Metrics struct {
	TicksTotal         *atomic.Uint64
	TicksDroppedRedis  *atomic.Uint64
	TicksDroppedCH     *atomic.Uint64
	RedisPublishErrors *atomic.Uint64
	CHBatchErrors      *atomic.Uint64
	CHRowsInserted     *atomic.Uint64
	CHDropped          *atomic.Uint64
	DriverReopens      *atomic.Uint64
}

// Register attaches the gauges to the given prometheus registry.  We
// deliberately use Gauges that read from atomic counters — that lets
// the sinks update them with one atomic.Add, with no Prometheus call
// on the hot path.
func (m *Metrics) Register(reg prometheus.Registerer) error {
	gauges := []struct {
		name  string
		help  string
		value *atomic.Uint64
	}{
		{"stockyard_quotes_ticks_total", "Ticks received from the driver.", m.TicksTotal},
		{"stockyard_quotes_ticks_dropped_redis_total", "Ticks dropped before Redis publish (queue full).", m.TicksDroppedRedis},
		{"stockyard_quotes_ticks_dropped_ch_total", "Ticks lost in flight to ClickHouse when shutdown cancelled the blocking send.", m.TicksDroppedCH},
		{"stockyard_quotes_redis_publish_errors_total", "Redis pipeline executions that returned an error.", m.RedisPublishErrors},
		{"stockyard_quotes_ch_batch_errors_total", "ClickHouse batches dropped after exhausted retries.", m.CHBatchErrors},
		{"stockyard_quotes_ch_rows_inserted_total", "Rows successfully inserted into quotes_ticks.", m.CHRowsInserted},
		{"stockyard_quotes_ch_rows_dropped_total", "Rows dropped without insertion (batch errors).", m.CHDropped},
		{"stockyard_quotes_driver_reopens_total", "Times /dev/stockyard was closed and reopened.", m.DriverReopens},
	}
	for _, g := range gauges {
		v := g.value
		c := prometheus.NewCounterFunc(
			prometheus.CounterOpts{Name: g.name, Help: g.help},
			func() float64 { return float64(v.Load()) },
		)
		if err := reg.Register(c); err != nil {
			return err
		}
	}
	return nil
}

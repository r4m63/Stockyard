// Package health exposes /healthz, /readyz, and /metrics on a small
// HTTP server.  Liveness depends only on the in-process tick flow;
// readiness pings the external dependencies.
package health

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/ClickHouse/clickhouse-go/v2/lib/driver"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
)

// LastTickAt is a heartbeat that every tick processor updates after a
// successful sink write.  Anyone reading the value gets the unix-nano
// timestamp of the most recent activity, or 0 if no tick has flowed yet.
type LastTickAt struct {
	v atomic.Int64
}

// Touch records "we just processed a tick".  Safe for many goroutines.
func (l *LastTickAt) Touch() { l.v.Store(time.Now().UnixNano()) }

// Age returns how long ago Touch was last called, or a very large
// duration if it has never been called.
func (l *LastTickAt) Age() time.Duration {
	n := l.v.Load()
	if n == 0 {
		return 1 << 62
	}
	return time.Since(time.Unix(0, n))
}

// Server wires the three endpoints together.  Start() blocks until ctx
// cancels, then performs a graceful Shutdown with a short deadline.
type Server struct {
	Addr        string
	Redis       *redis.Client
	CH          driver.Conn
	LastTick    *LastTickAt
	LivenessMax time.Duration // max age of last tick before liveness flips
	Log         *slog.Logger
}

// Run blocks until ctx is cancelled and the HTTP server is fully shut
// down.  Returns nil on a clean shutdown, or the listen error.
func (s *Server) Run(ctx context.Context) error {
	if s.LivenessMax <= 0 {
		s.LivenessMax = 5 * time.Second
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealthz)
	mux.HandleFunc("/readyz", s.handleReadyz)
	mux.Handle("/metrics", promhttp.Handler())

	srv := &http.Server{
		Addr:              s.Addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
		<-errCh // drain
		return nil
	case err := <-errCh:
		if err == http.ErrServerClosed {
			return nil
		}
		return err
	}
}

// handleHealthz: 200 if a tick has flowed within LivenessMax.  Returns
// 503 if the pipeline has stalled — orchestrators can restart us.
func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	age := s.LastTick.Age()
	if age > s.LivenessMax {
		http.Error(w, fmt.Sprintf("stale: last tick %s ago", age), http.StatusServiceUnavailable)
		return
	}
	fmt.Fprintf(w, "ok: last tick %s ago\n", age)
}

// handleReadyz: 200 only if Redis and CH ping cleanly.  503 on any
// failure — keeps load off a sink that just lost its backing store.
func (s *Server) handleReadyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 1*time.Second)
	defer cancel()

	if err := s.Redis.Ping(ctx).Err(); err != nil {
		http.Error(w, "redis: "+err.Error(), http.StatusServiceUnavailable)
		return
	}
	if err := s.CH.Ping(ctx); err != nil {
		http.Error(w, "clickhouse: "+err.Error(), http.StatusServiceUnavailable)
		return
	}
	fmt.Fprint(w, "ok\n")
}

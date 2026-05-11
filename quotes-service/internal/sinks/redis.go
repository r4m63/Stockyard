// Package sinks contains the two terminal writers: Redis (live fanout
// + last-quote cache + durable backup stream) and ClickHouse (history
// store, batched).  Both consume from channels filled by pipeline.Fanout.
package sinks

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/stockyard/quotes-service/internal/pipeline"
)

// StreamMaxLen caps the durable backup stream at ~100k entries
// (approximate trim, ~10× faster than exact).
const StreamMaxLen = 100_000

// RedisSink writes one tick to three Redis structures per call:
//
//	HSET    quotes:{ticker}        — last values for synchronous readers (Core).
//	PUBLISH channel:quotes:{ticker} — JSON push for Gateway WS fan-out.
//	XADD    stream:quotes           — durable backup, used by reconnect tools.
//
// The three commands are pipelined so each tick costs exactly one
// round-trip to Redis.  Errors are logged and counted; the tick is not
// retried (ADR-001).
type RedisSink struct {
	Client *redis.Client
	In     <-chan pipeline.Tick
	Log    *slog.Logger

	PublishErrors atomic.Uint64
	Published     atomic.Uint64
}

// jsonPayload is the wire format pushed to channel:quotes:{ticker}.
// Locked in ADR-011: all prices integer cents, ts ISO-8601, tsNs uint64.
type jsonPayload struct {
	Ticker    string `json:"ticker"`
	Ts        string `json:"ts"`
	TsNs      uint64 `json:"tsNs"`
	BidCents  int64  `json:"bidCents"`
	AskCents  int64  `json:"askCents"`
	LastCents int64  `json:"lastCents"`
	Volume    uint32 `json:"volume"`
}

// Run drains the input channel until it is closed or ctx is cancelled.
// Returns nil on a clean drain.
func (s *RedisSink) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return nil
		case t, ok := <-s.In:
			if !ok {
				return nil
			}
			if err := s.writeOne(ctx, t); err != nil {
				s.PublishErrors.Add(1)
				s.Log.Warn("redis sink write failed",
					slog.String("ticker", t.Ticker),
					slog.Any("err", err))
				continue
			}
			s.Published.Add(1)
		}
	}
}

// writeOne issues HSET + PUBLISH + XADD in one pipelined round-trip.
// The driver gives us CLOCK_MONOTONIC ns; we render an absolute wall-
// clock timestamp at publish time so consumers don't have to reverse
// the monotonic offset.
func (s *RedisSink) writeOne(ctx context.Context, t pipeline.Tick) error {
	tsWall := time.Now().UTC().Format("2006-01-02T15:04:05.000Z07:00")

	payload, err := json.Marshal(jsonPayload{
		Ticker:    t.Ticker,
		Ts:        tsWall,
		TsNs:      t.TsNs,
		BidCents:  t.BidCents,
		AskCents:  t.AskCents,
		LastCents: t.LastCents,
		Volume:    t.Volume,
	})
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	// TxPipeline wraps the three commands in MULTI/EXEC so the server
	// executes them atomically.  Without it, a context cancellation
	// between the HSET being processed and the PUBLISH being sent can
	// leave Redis HASH updated but no notification to WS subscribers —
	// they would see stale state on the next reconnect snapshot.
	pipe := s.Client.TxPipeline()
	pipe.HSet(ctx, "quotes:"+t.Ticker,
		"ts", tsWall,
		"ts_ns", t.TsNs,
		"bid", t.BidCents,
		"ask", t.AskCents,
		"last", t.LastCents,
		"volume", t.Volume,
	)
	pipe.Publish(ctx, "channel:quotes:"+t.Ticker, payload)
	pipe.XAdd(ctx, &redis.XAddArgs{
		Stream: "stream:quotes",
		MaxLen: StreamMaxLen,
		Approx: true,
		Values: map[string]interface{}{
			"ticker": t.Ticker,
			"ts_ns":  t.TsNs,
			"bid":    t.BidCents,
			"ask":    t.AskCents,
			"last":   t.LastCents,
			"volume": t.Volume,
		},
	})
	_, err = pipe.Exec(ctx)
	return err
}

package sinks

import (
	"context"
	"fmt"
	"log/slog"
	"math/big"
	"sync/atomic"
	"time"

	"github.com/ClickHouse/clickhouse-go/v2/lib/driver"

	"github.com/stockyard/quotes-service/internal/pipeline"
)

// ClickHouseSink batches incoming ticks and flushes them as a single
// INSERT.  A batch flushes when **either** Size ticks are buffered
// **or** Interval has elapsed since the last flush — whichever comes
// first.  Retries 3× on send failure with exponential backoff; on the
// fourth failure the batch is dropped and counted.
type ClickHouseSink struct {
	Conn     driver.Conn
	In       <-chan pipeline.Tick
	Size     int
	Interval time.Duration
	Log      *slog.Logger

	BatchErrors  atomic.Uint64
	BatchesSent  atomic.Uint64
	RowsInserted atomic.Uint64
	Dropped      atomic.Uint64
}

// Run drains In until it is closed, flushing on size or timer.  On
// shutdown (ctx done) it makes one last best-effort flush so we don't
// lose the partial batch.
func (s *ClickHouseSink) Run(ctx context.Context) error {
	batch := make([]pipeline.Tick, 0, s.Size)
	timer := time.NewTimer(s.Interval)
	defer timer.Stop()

	// flushSendCtx is used by every flush path.  When the outer ctx
	// has been cancelled the in-flight ticks must still be persisted,
	// so we synthesise a short standalone deadline for those writes
	// rather than re-using a cancelled ctx (which would short-circuit
	// `send` immediately).
	flushOnce := func() {
		if len(batch) == 0 {
			return
		}
		flushCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()

		// Prefer the retry path while the outer ctx is alive; on
		// shutdown there is no time for retries, so a single best-effort
		// send is used.
		var err error
		if ctx.Err() == nil {
			err = s.sendWithRetry(ctx, batch)
		} else {
			err = s.send(flushCtx, batch)
		}
		if err != nil {
			s.BatchErrors.Add(1)
			s.Dropped.Add(uint64(len(batch)))
			s.Log.Warn("clickhouse batch dropped",
				slog.Int("size", len(batch)),
				slog.Any("err", err))
		} else {
			s.BatchesSent.Add(1)
			s.RowsInserted.Add(uint64(len(batch)))
		}
		batch = batch[:0]
		resetTimer(timer, s.Interval)
	}

	for {
		select {
		case <-ctx.Done():
			flushOnce()
			return nil

		case t, ok := <-s.In:
			if !ok {
				flushOnce()
				return nil
			}
			batch = append(batch, t)
			if len(batch) >= s.Size {
				flushOnce()
			}

		case <-timer.C:
			flushOnce()
		}
	}
}

func (s *ClickHouseSink) sendWithRetry(ctx context.Context, batch []pipeline.Tick) error {
	delay := 100 * time.Millisecond
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := s.send(ctx, batch); err == nil {
			return nil
		} else {
			lastErr = err
			s.Log.Warn("clickhouse batch send failed; retrying",
				slog.Int("attempt", attempt),
				slog.Int("size", len(batch)),
				slog.Any("err", err))
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		delay *= 2
	}
	return lastErr
}

func (s *ClickHouseSink) send(ctx context.Context, batch []pipeline.Tick) error {
	pb, err := s.Conn.PrepareBatch(ctx,
		"INSERT INTO quotes_ticks (ticker, ts, bid, ask, last, volume)")
	if err != nil {
		return fmt.Errorf("prepare: %w", err)
	}
	for _, t := range batch {
		ts := time.Unix(0, int64(t.TsNs)).UTC()
		if err := pb.Append(
			t.Ticker,
			ts,
			centsToDecimal(t.BidCents),
			centsToDecimal(t.AskCents),
			centsToDecimal(t.LastCents),
			uint64(t.Volume),
		); err != nil {
			return fmt.Errorf("append: %w", err)
		}
	}
	if err := pb.Send(); err != nil {
		return fmt.Errorf("send: %w", err)
	}
	return nil
}

// centsToDecimal converts integer kopecks to a Decimal(18,4)-ready value.
// clickhouse-go v2 accepts *big.Int for Decimal columns and applies the
// column's scale to the integer.  Cents have scale 2; the column has
// scale 4; so we multiply by 100 to land at the right scale.
//
// The multiplication is done in big.Int to avoid int64 overflow for
// large cents values (anything above 9.2e16 would wrap if we computed
// `cents*100` as int64 before converting).
func centsToDecimal(cents int64) *big.Int {
	return new(big.Int).Mul(big.NewInt(cents), big.NewInt(100))
}

func resetTimer(t *time.Timer, d time.Duration) {
	if !t.Stop() {
		select {
		case <-t.C:
		default:
		}
	}
	t.Reset(d)
}

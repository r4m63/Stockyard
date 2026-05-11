package driver

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"time"

	"github.com/stockyard/quotes-service/internal/pipeline"
)

// ReadBatch is how many ticks one read syscall asks for.  The kernel
// driver will return as many as it has up to this limit.  64 × 44 =
// 2816 bytes — small enough to fit easily in any allocator's fast path.
const ReadBatch = 64

// Reader opens /dev/stockyard and forwards every received tick down
// the out channel.  On read errors it closes the fd, waits with
// exponential backoff, and reopens.  Cancelling the context stops the
// loop and closes the fd cleanly.
type Reader struct {
	Path   string
	Out    chan<- pipeline.Tick
	Log    *slog.Logger
	Reopen ReopenPolicy
}

// ReopenPolicy parameterises the backoff between reopen attempts.
// Defaults match the architect's recommendation: 100 ms → 1 s → 10 s.
type ReopenPolicy struct {
	Initial time.Duration
	Max     time.Duration
}

func (p ReopenPolicy) defaults() ReopenPolicy {
	if p.Initial <= 0 {
		p.Initial = 100 * time.Millisecond
	}
	if p.Max <= 0 {
		p.Max = 10 * time.Second
	}
	return p
}

// Run drives the read loop.  Returns when ctx is cancelled or when an
// unrecoverable error occurs (e.g. the path stops being a character
// device).  ctx.Err() is returned in the cancellation case so the
// caller can distinguish shutdown from failure.
func (r *Reader) Run(ctx context.Context) error {
	policy := r.Reopen.defaults()
	buf := make([]byte, ReadBatch*pipeline.TickSize)
	ticks := make([]pipeline.Tick, 0, ReadBatch)
	backoff := policy.Initial

	for {
		if err := ctx.Err(); err != nil {
			return err
		}

		f, err := os.OpenFile(r.Path, os.O_RDONLY, 0)
		if err != nil {
			r.logBackoff("open failed", err, backoff)
			if waitErr := sleepCtx(ctx, backoff); waitErr != nil {
				return waitErr
			}
			backoff = nextBackoff(backoff, policy.Max)
			continue
		}

		// Successful open resets the backoff so a flaky driver doesn't
		// accumulate delay over the process lifetime.
		backoff = policy.Initial
		r.Log.Info("driver opened", slog.String("path", r.Path))

		readErr := r.readLoop(ctx, f, buf, &ticks)
		_ = f.Close()

		if errors.Is(readErr, context.Canceled) || errors.Is(readErr, context.DeadlineExceeded) {
			return readErr
		}
		r.Log.Warn("driver read loop exited; reopening",
			slog.String("path", r.Path),
			slog.Any("err", readErr))
	}
}

// readLoop owns one open fd and pushes ticks until read returns an
// error.  Returns ctx.Err() on cancellation, or the read error.
func (r *Reader) readLoop(ctx context.Context, f *os.File, buf []byte, ticks *[]pipeline.Tick) error {
	for {
		n, err := f.Read(buf)
		if err != nil {
			if errors.Is(err, io.EOF) {
				// Preserve the io.EOF sentinel so callers up the chain
				// (and future logic that distinguishes "permanently
				// gone" from "transient") can still match it with
				// errors.Is.
				return fmt.Errorf("driver EOF: %w", io.EOF)
			}
			return err
		}
		if n == 0 {
			continue
		}
		if n%pipeline.TickSize != 0 {
			return fmt.Errorf("driver returned %d bytes, not a multiple of %d", n, pipeline.TickSize)
		}

		parsed, perr := Parse(buf[:n], *ticks)
		if perr != nil {
			return perr
		}
		*ticks = parsed

		for _, t := range parsed {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case r.Out <- t:
			}
		}
	}
}

func (r *Reader) logBackoff(msg string, err error, d time.Duration) {
	r.Log.Warn(msg,
		slog.String("path", r.Path),
		slog.Any("err", err),
		slog.Duration("retry_in", d))
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

func nextBackoff(cur, max time.Duration) time.Duration {
	next := cur * 2
	if next > max {
		next = max
	}
	return next
}

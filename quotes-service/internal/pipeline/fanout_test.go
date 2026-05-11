package pipeline

import (
	"context"
	"testing"
	"time"
)

func TestFanout_HappyPath(t *testing.T) {
	in := make(chan Tick, 100)
	f := NewFanout(in, 100, 100)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go f.Run(ctx)

	const N = 100
	for i := 0; i < N; i++ {
		in <- Tick{Ticker: "SBER", LastCents: int64(i)}
	}
	close(in)

	// Drain redis side
	redisGot := 0
	for range f.Redis {
		redisGot++
	}
	// Drain CH side
	chGot := 0
	for range f.CH {
		chGot++
	}

	if redisGot != N {
		t.Errorf("redis chan got %d, want %d", redisGot, N)
	}
	if chGot != N {
		t.Errorf("ch chan got %d, want %d", chGot, N)
	}
	if d := f.DroppedRedis.Load(); d != 0 {
		t.Errorf("DroppedRedis = %d, want 0", d)
	}
}

func TestFanout_RedisDropOnFull(t *testing.T) {
	// Tiny Redis buffer so anything more than 1 in flight is dropped.
	in := make(chan Tick, 100)
	f := NewFanout(in, 1, 100)

	// Pre-fill the Redis channel so the buffer stays full for the
	// entire test run; no reader of f.Redis exists, so every send
	// from the fanout loop must hit the `default:` arm and be counted.
	f.Redis <- Tick{Ticker: "PRE"}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Fast CH consumer so the fanout loop is never blocked on the CH
	// side and we measure only the Redis drop behaviour.
	chDone := make(chan struct{})
	go func() {
		for range f.CH {
		}
		close(chDone)
	}()
	go f.Run(ctx)

	const N = 100
	for i := 0; i < N; i++ {
		in <- Tick{Ticker: "SBER", LastCents: int64(i)}
	}
	close(in)

	// Wait for fanout to finish; CH chan close is our completion signal.
	select {
	case <-chDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for fanout to drain")
	}

	dropped := f.DroppedRedis.Load()
	if dropped != N {
		t.Errorf("DroppedRedis = %d, want %d", dropped, N)
	}
}

// TestFanout_DroppedCHCounterBumps is the M4 regression — when ctx
// fires while the fanout is blocked on a CH send (CH buffer full, no
// reader on the CH side), the popped tick is silently lost.  The
// counter exists so the loss is observable in Prometheus.
func TestFanout_DroppedCHCounterBumps(t *testing.T) {
	in := make(chan Tick, 1)
	f := NewFanout(in, 64, 1)

	// Pre-fill the CH channel so the next send blocks.  No reader on
	// the CH side means the send will stay blocked until ctx fires.
	f.CH <- Tick{Ticker: "PRE"}

	// Drain Redis so it's never the blocker.
	go func() {
		for range f.Redis {
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		f.Run(ctx)
		close(done)
	}()

	// Feed one tick: fanout pops it, sends to Redis OK, then blocks
	// trying to send to the full CH chan.
	in <- Tick{Ticker: "LOST"}

	// Give fanout a moment to reach the blocking CH send.
	time.Sleep(50 * time.Millisecond)

	// Cancel — the ctx.Done arm of the inner select must fire and
	// bump DroppedCH before returning.
	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("fanout did not exit after ctx cancel")
	}

	if got := f.DroppedCH.Load(); got != 1 {
		t.Errorf("DroppedCH = %d, want 1 (regression of M4)", got)
	}
}

func TestFanout_CtxCancelClosesOutputs(t *testing.T) {
	in := make(chan Tick) // unbuffered: fanout blocks waiting
	f := NewFanout(in, 4, 4)

	ctx, cancel := context.WithCancel(context.Background())
	go f.Run(ctx)

	// Drain CH so a partial fanout doesn't block on it.
	go func() {
		for range f.CH {
		}
	}()
	// Drain Redis likewise.
	go func() {
		for range f.Redis {
		}
	}()

	// Cancel without sending anything.
	cancel()

	// Both channels should close within a short window.
	done := make(chan struct{})
	go func() {
		<-recvUntilClose(f.Redis)
		<-recvUntilClose(f.CH)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("channels did not close within 1s of ctx cancel")
	}
}

func TestFanout_InClosedExits(t *testing.T) {
	in := make(chan Tick, 4)
	f := NewFanout(in, 4, 4)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go f.Run(ctx)

	in <- Tick{Ticker: "SBER"}
	in <- Tick{Ticker: "GAZP"}
	close(in)

	got := []Tick{}
	for t := range f.Redis {
		got = append(got, t)
	}
	if len(got) != 2 {
		t.Errorf("got %d ticks on Redis chan, want 2", len(got))
	}

	// CH should also be drained and closed.
	chGot := 0
	for range f.CH {
		chGot++
	}
	if chGot != 2 {
		t.Errorf("got %d ticks on CH chan, want 2", chGot)
	}
}

// recvUntilClose returns a channel that signals once `ch` is closed.
// Helps the cancel-test wait without polling.
func recvUntilClose(ch <-chan Tick) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		for range ch {
		}
		close(done)
	}()
	return done
}

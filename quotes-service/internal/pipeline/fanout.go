package pipeline

import (
	"context"
	"sync/atomic"
)

// Fanout reads from In and forwards every tick to two output channels
// with **different** backpressure policies:
//
//   - Redis is best-effort (ADR-001 at-most-once for the live channel);
//     on a full buffer we drop the tick and bump DroppedRedis.
//   - ClickHouse is durable-buffered; the sender BLOCKS until the
//     batcher has space, because CH-bound ticks are the source of truth
//     for history.  If CH falls behind for long enough we are forced to
//     apply backpressure all the way to the driver — that is the
//     intended behaviour.
//
// Stop draining a channel by cancelling ctx; Fanout closes Redis and CH
// channels when ctx is done so downstream sinks see a clean EOF.
type Fanout struct {
	In    <-chan Tick
	Redis chan Tick
	CH    chan Tick

	DroppedRedis atomic.Uint64
	// DroppedCH counts ticks that were popped from In but never
	// reached CH because ctx was cancelled mid-send.  Only happens
	// during shutdown under load.  ADR-001 allows the loss; the
	// counter makes it observable.
	DroppedCH atomic.Uint64
}

// NewFanout builds a Fanout with the given buffer sizes.  redisBuf is
// usually small (e.g. 256) since we drop on overflow anyway; chBuf is
// the deep buffer used while the CH sink is mid-batch.
func NewFanout(in <-chan Tick, redisBuf, chBuf int) *Fanout {
	return &Fanout{
		In:    in,
		Redis: make(chan Tick, redisBuf),
		CH:    make(chan Tick, chBuf),
	}
}

// Run blocks until ctx is cancelled or In is closed.  On exit it
// closes both output channels so the sinks can drain and stop.
func (f *Fanout) Run(ctx context.Context) {
	defer close(f.Redis)
	defer close(f.CH)
	for {
		select {
		case <-ctx.Done():
			return
		case t, ok := <-f.In:
			if !ok {
				return
			}
			// Redis: drop-on-full.
			select {
			case f.Redis <- t:
			default:
				f.DroppedRedis.Add(1)
			}
			// CH: block until there is room.  Honour ctx during the wait
			// so shutdown is not held up indefinitely.  When ctx fires
			// here the popped tick is lost — bump DroppedCH so the loss
			// is at least visible in Prometheus.
			select {
			case <-ctx.Done():
				f.DroppedCH.Add(1)
				return
			case f.CH <- t:
			}
		}
	}
}

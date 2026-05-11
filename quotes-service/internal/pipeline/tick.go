// Package pipeline holds the in-process tick types and the fanout
// that splits a single driver-read stream into per-sink streams.
package pipeline

// TickSize matches sizeof(struct stockyard_tick) from
// driver/stockyard_driver.h.  44 bytes, packed, little-endian:
//
//	offset  size  field
//	    0     8    ticker[8]   null-padded ASCII
//	    8     8    ts_ns       uint64, CLOCK_MONOTONIC at gen
//	   16     8    bid_cents   int64
//	   24     8    ask_cents   int64
//	   32     8    last_cents  int64
//	   40     4    volume      uint32
//
// See .claude/tasks/TASK-008-c-driver.md for the contract sign-off
// (architect originally wrote 40, arithmetic gives 44, reviewer
// approved 44 with `volume` retained).
const TickSize = 44

// Tick is one quote sample carried through the in-process pipeline.
// Prices stay in integer kopecks (cents) everywhere; the sole
// conversion to Decimal(18,4) happens in the ClickHouse sink.
type Tick struct {
	Ticker    string
	TsNs      uint64
	BidCents  int64
	AskCents  int64
	LastCents int64
	Volume    uint32
}

// Package driver consumes the packed-binary tick stream coming out of
// /dev/stockyard.  parser.go decodes the 44-byte wire format; reader.go
// drives the read loop and reopen logic.
package driver

import (
	"encoding/binary"
	"errors"

	"github.com/stockyard/quotes-service/internal/pipeline"
)

// ErrShortBuffer reports a read whose length is not a multiple of
// pipeline.TickSize.  The kernel driver guarantees aligned reads, so
// this is always a userspace bug or a corrupted stream.
var ErrShortBuffer = errors.New("buffer length is not a multiple of TickSize")

// Parse decodes a contiguous run of N packed-tick records into N
// pipeline.Ticks.  len(buf) must be a multiple of pipeline.TickSize;
// otherwise Parse returns ErrShortBuffer without touching out.
//
// The caller owns `out`; Parse may grow it via append.  Reusing the
// same slice across reads is the expected pattern (zero allocations
// per tick after the slice reaches its steady size).
func Parse(buf []byte, out []pipeline.Tick) ([]pipeline.Tick, error) {
	if len(buf)%pipeline.TickSize != 0 {
		return out, ErrShortBuffer
	}
	n := len(buf) / pipeline.TickSize
	if cap(out) < n {
		out = make([]pipeline.Tick, 0, n)
	} else {
		out = out[:0]
	}
	for i := 0; i < n; i++ {
		base := i * pipeline.TickSize
		out = append(out, parseOne(buf[base:base+pipeline.TickSize]))
	}
	return out, nil
}

// parseOne reads exactly one 44-byte tick.  The caller has already
// verified the slice length.  Offsets match the C struct layout.
func parseOne(b []byte) pipeline.Tick {
	return pipeline.Tick{
		Ticker:    trimTicker(b[0:8]),
		TsNs:      binary.LittleEndian.Uint64(b[8:16]),
		BidCents:  int64(binary.LittleEndian.Uint64(b[16:24])),
		AskCents:  int64(binary.LittleEndian.Uint64(b[24:32])),
		LastCents: int64(binary.LittleEndian.Uint64(b[32:40])),
		Volume:    binary.LittleEndian.Uint32(b[40:44]),
	}
}

// trimTicker strips the null padding from the 8-byte ticker field.
// "SBER\0\0\0\0" → "SBER".  Non-ASCII bytes before the first null are
// preserved verbatim; the driver only ever writes uppercase ASCII.
func trimTicker(b []byte) string {
	end := len(b)
	for i, c := range b {
		if c == 0 {
			end = i
			break
		}
	}
	return string(b[:end])
}

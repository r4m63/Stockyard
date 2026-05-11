package driver

import (
	"bytes"
	"testing"

	"github.com/stockyard/quotes-service/internal/pipeline"
)

// goldenSBER is the deterministic fixture locked in TASK-008 B1
// (cross-arch verified between macOS arm64 and Linux arm64):
//
//	ticker="SBER", ts_ns=0, bid=28550, ask=28570, last=28560, vol=12345
const goldenLayout = "SBER + ts_ns=0 + bid=28550 + ask=28570 + last=28560 + vol=12345"

var goldenBytes = []byte{
	// offset  0-7   ticker[8] = "SBER\0\0\0\0"
	0x53, 0x42, 0x45, 0x52, 0x00, 0x00, 0x00, 0x00,
	// offset  8-15  ts_ns = 0
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	// offset 16-23  bid_cents = 28550 = 0x6F86
	0x86, 0x6F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	// offset 24-31  ask_cents = 28570 = 0x6F9A
	0x9A, 0x6F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	// offset 32-39  last_cents = 28560 = 0x6F90
	0x90, 0x6F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	// offset 40-43  volume = 12345 = 0x3039
	0x39, 0x30, 0x00, 0x00,
}

func TestParse_GoldenFixture(t *testing.T) {
	if len(goldenBytes) != pipeline.TickSize {
		t.Fatalf("fixture length %d, want %d", len(goldenBytes), pipeline.TickSize)
	}

	got, err := Parse(goldenBytes, nil)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("got %d ticks, want 1", len(got))
	}

	want := pipeline.Tick{
		Ticker:    "SBER",
		TsNs:      0,
		BidCents:  28550,
		AskCents:  28570,
		LastCents: 28560,
		Volume:    12345,
	}
	if got[0] != want {
		t.Errorf("layout: %s\n got: %+v\nwant: %+v", goldenLayout, got[0], want)
	}
}

func TestParse_ErrShortBuffer(t *testing.T) {
	cases := []int{1, 43, 45, 87} // not multiples of 44
	for _, n := range cases {
		buf := make([]byte, n)
		_, err := Parse(buf, nil)
		if err != ErrShortBuffer {
			t.Errorf("Parse(len=%d): got err=%v, want ErrShortBuffer", n, err)
		}
	}
}

func TestParse_EmptyBuffer(t *testing.T) {
	got, err := Parse(nil, nil)
	if err != nil {
		t.Fatalf("Parse(nil): %v", err)
	}
	if len(got) != 0 {
		t.Errorf("got %d ticks, want 0", len(got))
	}
}

func TestParse_MultiTick(t *testing.T) {
	// Two ticks back-to-back: SBER then GAZP-like values.
	buf := bytes.Clone(goldenBytes)
	second := bytes.Clone(goldenBytes)
	copy(second[0:8], []byte("GAZP\x00\x00\x00\x00"))
	// bid=16500=0x4074, ask=16520=0x4088, last=16510=0x407E, vol=999=0x3E7
	second[16], second[17] = 0x74, 0x40
	second[24], second[25] = 0x88, 0x40
	second[32], second[33] = 0x7E, 0x40
	second[40], second[41] = 0xE7, 0x03
	second[42], second[43] = 0x00, 0x00
	buf = append(buf, second...)

	got, err := Parse(buf, nil)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d ticks, want 2", len(got))
	}
	if got[0].Ticker != "SBER" || got[0].BidCents != 28550 {
		t.Errorf("tick[0] = %+v", got[0])
	}
	if got[1].Ticker != "GAZP" || got[1].BidCents != 16500 ||
		got[1].AskCents != 16520 || got[1].LastCents != 16510 || got[1].Volume != 999 {
		t.Errorf("tick[1] = %+v", got[1])
	}
}

func TestTrimTicker(t *testing.T) {
	tests := []struct {
		name string
		in   []byte
		want string
	}{
		{"null padded", []byte("SBER\x00\x00\x00\x00"), "SBER"},
		{"all bytes used", []byte("LONGNAME"), "LONGNAME"},
		{"first byte null", []byte{0, 0, 0, 0, 0, 0, 0, 0}, ""},
		{"trailing null only", []byte("ABCDEFG\x00"), "ABCDEFG"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := trimTicker(tc.in); got != tc.want {
				t.Errorf("trimTicker(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestParse_ReuseSlice(t *testing.T) {
	// First call grows the slice; second call should not reallocate.
	buf := bytes.Repeat(goldenBytes, 4)
	first, err := Parse(buf, nil)
	if err != nil {
		t.Fatalf("first Parse: %v", err)
	}
	capBefore := cap(first)

	second, err := Parse(buf, first)
	if err != nil {
		t.Fatalf("second Parse: %v", err)
	}
	if cap(second) != capBefore {
		t.Errorf("slice was reallocated: cap %d → %d", capBefore, cap(second))
	}
	if len(second) != 4 {
		t.Errorf("got %d ticks, want 4", len(second))
	}
}

// BenchmarkParse measures steady-state parse speed.  Useful to spot
// regressions; not part of correctness gates.
func BenchmarkParse(b *testing.B) {
	// 64 ticks per call — same shape as driver.ReadBatch.
	buf := bytes.Repeat(goldenBytes, 64)
	out := make([]pipeline.Tick, 0, 64)

	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var err error
		out, err = Parse(buf, out)
		if err != nil {
			b.Fatal(err)
		}
	}
}

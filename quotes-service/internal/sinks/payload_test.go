package sinks

import (
	"encoding/json"
	"math/big"
	"strings"
	"testing"
)

// TestJSONPayload_Schema locks the ADR-011 wire format for Redis
// Pub/Sub.  Gateway (TASK-010) parses these bytes; any drift here
// silently breaks WS consumers.
func TestJSONPayload_Schema(t *testing.T) {
	p := jsonPayload{
		Ticker:    "SBER",
		Ts:        "2026-05-11T12:34:56.789Z",
		TsNs:      1746789296789012345,
		BidCents:  28550,
		AskCents:  28570,
		LastCents: 28560,
		Volume:    12345,
	}
	raw, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(raw)

	// Field names — camelCase per project convention.
	mustContain(t, s, `"ticker":"SBER"`)
	mustContain(t, s, `"ts":"2026-05-11T12:34:56.789Z"`)
	mustContain(t, s, `"tsNs":1746789296789012345`)
	mustContain(t, s, `"bidCents":28550`)
	mustContain(t, s, `"askCents":28570`)
	mustContain(t, s, `"lastCents":28560`)
	mustContain(t, s, `"volume":12345`)

	// Integer cents, never decimal — the whole point of ADR-011.
	// The `ts` field is an ISO-8601 string with a fractional-second
	// dot, so we check the price/volume fields specifically rather
	// than scanning the whole payload for ".".
	for _, field := range []string{"bidCents", "askCents", "lastCents", "volume"} {
		assertNoDecimal(t, s, field)
	}
}

// assertNoDecimal asserts that the value rendered after `"name":` is
// not a JSON number containing a decimal point.  Defensive against a
// future producer accidentally emitting floats.
func assertNoDecimal(t *testing.T, payload, name string) {
	t.Helper()
	prefix := `"` + name + `":`
	i := strings.Index(payload, prefix)
	if i < 0 {
		t.Errorf("payload missing field %q", name)
		return
	}
	// Read until the next comma or closing brace.
	rest := payload[i+len(prefix):]
	end := strings.IndexAny(rest, ",}")
	if end < 0 {
		end = len(rest)
	}
	val := rest[:end]
	if strings.Contains(val, ".") {
		t.Errorf("field %q rendered with a decimal point: %q", name, val)
	}
}

func TestJSONPayload_NegativeCentsRoundTrip(t *testing.T) {
	// Prices in the simulator can be small enough that some operations
	// produce negative values during arithmetic.  Final values are
	// clamped to >=1 in the driver, but we shouldn't lose sign on
	// transport in case a future producer ever emits one.
	original := jsonPayload{
		Ticker:    "TEST",
		Ts:        "2026-01-01T00:00:00.000Z",
		TsNs:      0,
		BidCents:  -1,
		AskCents:  1,
		LastCents: 0,
		Volume:    0,
	}
	raw, _ := json.Marshal(original)
	var back jsonPayload
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if back != original {
		t.Errorf("round-trip mismatch:\n got: %+v\nwant: %+v", back, original)
	}
}

// TestCentsToDecimal pins the cents → Decimal(18,4) conversion that
// the ClickHouse sink relies on.  CH column scale is 4; cents are
// scale 2; multiplying by 100 lands on the correct scale-adjusted
// integer that the driver wraps into a Decimal.
func TestCentsToDecimal(t *testing.T) {
	tests := []struct {
		name  string
		cents int64
		want  int64 // expected scale-4 integer
	}{
		{"normal SBER", 28550, 2855000},
		{"one kopeck", 1, 100},
		{"zero", 0, 0},
		{"negative", -42, -4200},
		{"large", 1_000_000_000, 100_000_000_000},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := centsToDecimal(tc.cents)
			want := big.NewInt(tc.want)
			if got.Cmp(want) != 0 {
				t.Errorf("centsToDecimal(%d) = %s, want %s", tc.cents, got, want)
			}
		})
	}
}

// TestCentsToDecimal_Overflow is the H1 regression — catches a future
// regression to `big.NewInt(cents * 100)` where the multiplication
// is done in int64 and wraps for cents > ~9.2e16.
func TestCentsToDecimal_Overflow(t *testing.T) {
	tests := []struct {
		name  string
		cents int64
	}{
		{"1e17", 100_000_000_000_000_000}, // 10^17 — int64 mul × 100 wraps
		{"max int64", 9_223_372_036_854_775_807},
		{"min int64", -9_223_372_036_854_775_808},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := centsToDecimal(tc.cents)

			// Compute the expected value entirely in big.Int (no overflow).
			want := new(big.Int).Mul(big.NewInt(tc.cents), big.NewInt(100))

			if got.Cmp(want) != 0 {
				t.Errorf("centsToDecimal(%d) = %s, want %s (regression of H1)",
					tc.cents, got, want)
			}
			// Sanity: the result must be exactly 100× the input even at
			// extreme values.  Naive int64 mul would wrap to negatives.
			expectedSign := 1
			if tc.cents < 0 {
				expectedSign = -1
			}
			if tc.cents == 0 {
				expectedSign = 0
			}
			if got.Sign() != expectedSign {
				t.Errorf("centsToDecimal(%d) sign = %d, want %d (overflow regression)",
					tc.cents, got.Sign(), expectedSign)
			}
		})
	}
}

func mustContain(t *testing.T, s, sub string) {
	t.Helper()
	if !strings.Contains(s, sub) {
		t.Errorf("missing %q in:\n%s", sub, s)
	}
}

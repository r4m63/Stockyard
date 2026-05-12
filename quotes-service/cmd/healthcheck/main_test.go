package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestProbe_200_ReturnsNil(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/healthz" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	if err := probe(srv.URL+"/healthz", time.Second); err != nil {
		t.Fatalf("probe: expected nil, got %v", err)
	}
}

func TestProbe_503_ReturnsStatusError(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "stale: last tick 5s ago", http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	err := probe(srv.URL+"/healthz", time.Second)
	if err == nil {
		t.Fatal("probe: expected error for 503, got nil")
	}
	if !strings.Contains(err.Error(), "503") {
		t.Fatalf("probe: error must mention 503 status, got %q", err.Error())
	}
}

func TestProbe_ConnectionRefused_ReturnsError(t *testing.T) {
	t.Parallel()
	// Reserve a port by spinning a server then closing it — leaves a
	// guaranteed-dead address for the probe.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	url := srv.URL
	srv.Close()

	err := probe(url+"/healthz", time.Second)
	if err == nil {
		t.Fatal("probe: expected error for closed server, got nil")
	}
}

func TestProbe_Timeout_ReturnsError(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	err := probe(srv.URL+"/healthz", 50*time.Millisecond)
	if err == nil {
		t.Fatal("probe: expected timeout error, got nil")
	}
}

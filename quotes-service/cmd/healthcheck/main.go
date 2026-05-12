// Package main is a tiny HTTP probe used as the Docker HEALTHCHECK
// command for the distroless quotes-service image (no shell, curl or
// wget available).  Exits 0 when /healthz on 127.0.0.1:STOCKYARD_HEALTH_PORT
// returns 200, non-zero otherwise.
package main

import (
	"fmt"
	"net/http"
	"os"
	"time"
)

const defaultPort = "8080"

// probe issues a single GET to url with the given timeout and returns
// nil on HTTP 200, otherwise an error describing the failure.  Pulled
// out of main so tests can exercise it without spawning a subprocess.
func probe(url string, timeout time.Duration) error {
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %s", resp.Status)
	}
	return nil
}

func main() {
	port := os.Getenv("STOCKYARD_HEALTH_PORT")
	if port == "" {
		port = defaultPort
	}

	if err := probe("http://127.0.0.1:"+port+"/healthz", 2*time.Second); err != nil {
		fmt.Fprintln(os.Stderr, "healthcheck: "+err.Error())
		os.Exit(1)
	}
}

// Package api holds the worker's HTTP handlers.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"nimbus/worker/internal/config"
)

type configState struct {
	WorkerID     string `json:"workerId"`
	ParallelJobs int    `json:"parallelJobs"`
	LeaseSeconds int    `json:"leaseSeconds"`
}

func currentState() configState {
	return configState{
		WorkerID:     config.Get().WorkerID,
		ParallelJobs: config.ParallelJobs(),
		LeaseSeconds: int(config.LeaseDuration().Seconds()),
	}
}

// PutConfig handles PUT /config with a body like {"parallelJobs": 8, "leaseSeconds": 600}. Either field may be
// left out, but not both. It changes the number of jobs the worker may run in parallel and/or how long a newly
// claimed job is leased for. Changes take effect immediately (a lease already taken keeps its length), apply
// all together or not at all, and are not saved: a restart goes back to the starting values. The response is
// the resulting configuration.
func PutConfig(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<10)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()

	var req struct { // pointers, so a missing field can be told from 0
		ParallelJobs *int `json:"parallelJobs"`
		LeaseSeconds *int `json:"leaseSeconds"`
	}
	if err := dec.Decode(&req); err != nil || dec.Decode(&struct{}{}) != io.EOF {
		writeError(w, http.StatusBadRequest, `the body must be JSON like {"parallelJobs": 4, "leaseSeconds": 1800}`)
		return
	}
	if req.ParallelJobs == nil && req.LeaseSeconds == nil {
		writeError(w, http.StatusBadRequest, "give parallelJobs and/or leaseSeconds")
		return
	}

	before := currentState()
	if err := config.Update(config.Changes{ParallelJobs: req.ParallelJobs, LeaseSeconds: req.LeaseSeconds}); err != nil {
		writeError(w, http.StatusBadRequest, strings.ReplaceAll(err.Error(), "\n", "; ")) // one line, even for several problems
		return
	}
	after := currentState()
	slog.Info("config updated",
		"parallel_jobs_from", before.ParallelJobs, "parallel_jobs_to", after.ParallelJobs,
		"lease_seconds_from", before.LeaseSeconds, "lease_seconds_to", after.LeaseSeconds)

	writeJSON(w, http.StatusOK, after)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil && !errors.Is(err, http.ErrHandlerTimeout) {
		slog.Warn("writing response failed", "error", err)
	}
}

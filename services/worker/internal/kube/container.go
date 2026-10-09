// Package kube deploys one container of an app to Kubernetes: it turns the container's design into Kubernetes objects,
// applies them, and watches the rollout until the container is ready or has clearly failed.
//
// Everything an app owns lives in its own namespace (nimbus-app-<app id>), labelled so it can be found and cleaned up.
package kube

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// Container is one container of the app's graph, as the backend stores it for a job (the spec of a deployment task).
type Container struct {
	Name        string   `json:"name"`
	Image       string   `json:"image"`
	Port        *int     `json:"port"`
	Expose      bool     `json:"expose"`
	Kind        string   `json:"kind"` // "stateless" or "stateful"
	Replicas    int      `json:"replicas"`
	MinReplicas *int     `json:"minReplicas"`
	MaxReplicas *int     `json:"maxReplicas"`
	CPUTarget   *int     `json:"cpuTarget"`
	Env         []EnvVar `json:"env"`
	Volume      *Volume  `json:"volume"`
}

type EnvVar struct {
	Key    string  `json:"key"`
	Value  *string `json:"value"`
	Secret bool    `json:"secret"`
}

type Volume struct {
	Size      string `json:"size"`
	MountPath string `json:"mountPath"`
}

// Autoscaled is true when the design asks for the replica count to follow load.
func (c Container) Autoscaled() bool {
	return c.Kind == "stateless" && (c.MinReplicas != nil || c.MaxReplicas != nil || c.CPUTarget != nil)
}

// ErrNotSupported is for parts of a design the runner can't deploy yet. The job fails with this message rather than
// being deployed with something quietly left out.
var ErrNotSupported = errors.New("not supported yet")

// ParseContainer reads a job's spec and checks that everything in it can be deployed.
func ParseContainer(raw json.RawMessage) (Container, error) {
	var c Container
	if err := json.Unmarshal(raw, &c); err != nil {
		return Container{}, fmt.Errorf("reading the container's design: %w", err)
	}
	if strings.TrimSpace(c.Name) == "" || strings.TrimSpace(c.Image) == "" {
		return Container{}, errors.New("the container needs a name and an image")
	}
	if c.Replicas < 1 {
		c.Replicas = 1
	}
	if c.Kind == "stateful" || c.Volume != nil {
		return Container{}, fmt.Errorf("stateful containers (volumes): %w", ErrNotSupported)
	}
	var secrets []string
	for _, e := range c.Env {
		if e.Secret {
			secrets = append(secrets, e.Key)
		}
	}
	if len(secrets) > 0 {
		return Container{}, fmt.Errorf("secret environment variables (%s): %w", strings.Join(secrets, ", "), ErrNotSupported)
	}
	return c, nil
}

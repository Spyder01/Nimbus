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

	"k8s.io/apimachinery/pkg/api/resource"
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

// Stateful containers run as a StatefulSet with a volume that outlives their pods.
func (c Container) Stateful() bool { return c.Kind == "stateful" }

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
	if c.Volume != nil && c.Kind != "stateful" {
		return Container{}, errors.New("only a stateful container can have a volume")
	}
	if c.Stateful() {
		if c.Volume == nil {
			return Container{}, errors.New("a stateful container needs a volume")
		}
		if _, err := resource.ParseQuantity(c.Volume.Size); err != nil || !strings.HasPrefix(c.Volume.MountPath, "/") {
			return Container{}, fmt.Errorf("the volume needs a size like 1Gi and an absolute mount path (got %q at %q)", c.Volume.Size, c.Volume.MountPath)
		}
		// More than one replica of a database is not a replicated database: each would get its own empty volume.
		if c.Replicas > 1 {
			return Container{}, fmt.Errorf("stateful containers with more than one replica: %w", ErrNotSupported)
		}
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

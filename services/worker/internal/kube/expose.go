package kube

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
)

// Public containers get a web address through the Gateway API: one shared Gateway is the way in, and each public
// container has an HTTPRoute that sends a hostname of its own to the container's Service. Only standard Gateway API
// objects are used, so any conformant controller will do.
//
// The address is http://<container>-<first 8 characters of the app id>.<base domain>. The app id part keeps names unique
// across apps. HTTPS comes later.

var routeGVR = schema.GroupVersionResource{Group: "gateway.networking.k8s.io", Version: "v1", Resource: "httproutes"}

// MaxPublicName is the longest container name that can be public: "<name>-<8 characters>" has to fit in a DNS label (63).
const MaxPublicName = 54

// Expose says where public containers get their addresses. The zero value means exposing isn't set up.
type Expose struct {
	// BaseDomain is what addresses end in, e.g. "localhost" or "apps.example.com".
	BaseDomain string
	// GatewayNamespace and GatewayName identify the shared Gateway the routes attach to.
	GatewayNamespace, GatewayName string
}

func (e Expose) Configured() bool { return e.BaseDomain != "" && e.GatewayName != "" }

var domainRe = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$`)

// NewExpose checks and builds the settings. gateway is "<namespace>/<name>". Both empty means exposing is off.
func NewExpose(baseDomain, gateway string) (Expose, error) {
	baseDomain = strings.ToLower(strings.Trim(strings.TrimSpace(baseDomain), "."))
	if baseDomain == "" && gateway == "" {
		return Expose{}, nil
	}
	if baseDomain == "" || gateway == "" {
		return Expose{}, errors.New("set both WORKER_BASE_DOMAIN and WORKER_GATEWAY (or neither, to leave public containers off)")
	}
	if !domainRe.MatchString(baseDomain) || len(baseDomain) > 200 {
		return Expose{}, fmt.Errorf("WORKER_BASE_DOMAIN %q isn't a domain name like apps.example.com", baseDomain)
	}
	ns, name, ok := strings.Cut(gateway, "/")
	if !ok || ns == "" || name == "" || strings.Contains(name, "/") {
		return Expose{}, fmt.Errorf("WORKER_GATEWAY must be <namespace>/<name>, got %q", gateway)
	}
	return Expose{BaseDomain: baseDomain, GatewayNamespace: ns, GatewayName: name}, nil
}

// Host is the public hostname of a container.
func (e Expose) Host(appID, name string) (string, error) {
	if len(name) > MaxPublicName {
		return "", fmt.Errorf("the name %q is too long for a public container (at most %d characters)", name, MaxPublicName)
	}
	short, _, _ := strings.Cut(appID, "-") // the first 8 characters of a UUID
	return fmt.Sprintf("%s-%s.%s", name, short, e.BaseDomain), nil
}

// URL is where a public container can be opened.
func (e Expose) URL(appID, name string) (string, error) {
	host, err := e.Host(appID, name)
	if err != nil {
		return "", err
	}
	return "http://" + host, nil
}

// HTTPRoute sends the container's hostname to its Service. Needs a port (and so a Service).
func HTTPRoute(appID, deploymentID string, c Container, e Expose) (*unstructured.Unstructured, error) {
	if c.Port == nil {
		return nil, errors.New("a public container needs a port")
	}
	host, err := e.Host(appID, c.Name)
	if err != nil {
		return nil, err
	}
	lbls := map[string]any{}
	for k, v := range labels(appID, c.Name) {
		lbls[k] = v
	}
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": "gateway.networking.k8s.io/v1",
		"kind":       "HTTPRoute",
		"metadata": map[string]any{
			"name": c.Name, "namespace": NamespaceFor(appID), "labels": lbls,
			"annotations": map[string]any{annotationDeploy: deploymentID},
		},
		"spec": map[string]any{
			"parentRefs": []any{map[string]any{
				"group": "gateway.networking.k8s.io", "kind": "Gateway",
				"name": e.GatewayName, "namespace": e.GatewayNamespace,
			}},
			"hostnames": []any{host},
			"rules": []any{map[string]any{
				"backendRefs": []any{map[string]any{"name": c.Name, "port": int64(*c.Port)}},
			}},
		},
	}}, nil
}

// ErrNoGatewayAPI is for a cluster that has no HTTPRoute resource: the Gateway API isn't installed.
var ErrNoGatewayAPI = errors.New("this cluster doesn't have the Gateway API installed (there is no HTTPRoute resource)")

// ApplyRoute creates or updates the container's route.
func (d *Deployer) ApplyRoute(ctx context.Context, appID, deploymentID string, c Container, e Expose) error {
	route, err := HTTPRoute(appID, deploymentID, c, e)
	if err != nil {
		return err
	}
	data, err := route.MarshalJSON()
	if err != nil {
		return fmt.Errorf("encoding the route: %w", err)
	}
	_, err = d.dyn.Resource(routeGVR).Namespace(NamespaceFor(appID)).Patch(ctx, c.Name, types.ApplyPatchType, data, patchOptions())
	if apierrors.IsNotFound(err) {
		return ErrNoGatewayAPI
	}
	if err != nil {
		return fmt.Errorf("applying the route: %w", err)
	}
	return nil
}

// DeleteRoute removes the container's route if there is one. A cluster without the Gateway API has none, which is fine.
func (d *Deployer) DeleteRoute(ctx context.Context, appID, name string) error {
	err := d.dyn.Resource(routeGVR).Namespace(NamespaceFor(appID)).Delete(ctx, name, metav1.DeleteOptions{})
	if err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("removing the route: %w", err)
	}
	return nil
}

// WaitRouteAccepted returns once a Gateway has accepted the route and resolved its backend, or an error saying why one
// won't (typically: no such Gateway, or its listener doesn't allow routes from this namespace).
func (d *Deployer) WaitRouteAccepted(ctx context.Context, appID, name string, timeout time.Duration) error {
	ns := NamespaceFor(appID)
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(pollEvery)
	defer tick.Stop()
	last := "no Gateway has picked it up yet"
	for {
		u, err := d.dyn.Resource(routeGVR).Namespace(ns).Get(ctx, name, metav1.GetOptions{})
		switch {
		case err != nil && ctx.Err() != nil:
			return ctx.Err()
		case err != nil:
			last = "can't read the route: " + err.Error()
		default:
			ok, failure := routeStatus(u)
			if ok {
				return nil
			}
			if failure != "" {
				return fmt.Errorf("the Gateway didn't accept the public address: %s", failure)
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return fmt.Errorf("the public address wasn't ready after %s: %s", timeout.Round(time.Second), last)
		case <-tick.C:
		}
	}
}

// routeStatus reads a route's status: accepted (and its backend found) by every Gateway it names, or the reason one
// refused it. Neither means no Gateway has reported yet.
func routeStatus(u *unstructured.Unstructured) (accepted bool, failure string) {
	parents, _, _ := unstructured.NestedSlice(u.Object, "status", "parents")
	if len(parents) == 0 {
		return false, ""
	}
	for _, p := range parents {
		pm, _ := p.(map[string]any)
		conds, _ := pm["conditions"].([]any)
		ok := map[string]bool{}
		for _, c := range conds {
			cm, _ := c.(map[string]any)
			typ, _ := cm["type"].(string)
			status, _ := cm["status"].(string)
			if typ != "Accepted" && typ != "ResolvedRefs" {
				continue
			}
			if status == "False" {
				reason, _ := cm["reason"].(string)
				msg, _ := cm["message"].(string)
				return false, strings.TrimSpace(typ + " " + reason + ": " + shorten(msg, 200))
			}
			ok[typ] = status == "True"
		}
		if !ok["Accepted"] || !ok["ResolvedRefs"] {
			return false, "" // still being processed
		}
	}
	return true, ""
}

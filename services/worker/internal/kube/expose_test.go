package kube

import (
	"strings"
	"testing"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

func TestNewExposeChecksTheSettings(t *testing.T) {
	e, err := NewExpose(" Apps.Example.COM. ", "nimbus-gateway/nimbus")
	if err != nil || e.BaseDomain != "apps.example.com" || e.GatewayNamespace != "nimbus-gateway" || e.GatewayName != "nimbus" || !e.Configured() {
		t.Errorf("good settings: %+v %v", e, err)
	}
	if e, err := NewExpose("", ""); err != nil || e.Configured() {
		t.Errorf("neither set means off, not an error: %+v %v", e, err)
	}
	for name, in := range map[string][2]string{
		"only a domain":  {"apps.example.com", ""},
		"only a gateway": {"", "ns/gw"},
		"bad domain":     {"not a domain", "ns/gw"},
		"underscore":     {"my_apps.example.com", "ns/gw"},
		"no namespace":   {"apps.example.com", "gw"},
		"empty name":     {"apps.example.com", "ns/"},
		"too many parts": {"apps.example.com", "a/b/c"},
	} {
		if _, err := NewExpose(in[0], in[1]); err == nil {
			t.Errorf("%s should be refused", name)
		}
	}
	if _, err := NewExpose("localhost", "ns/gw"); err != nil {
		t.Errorf("localhost must be allowed: %v", err)
	}
}

func TestHostAndURL(t *testing.T) {
	e, _ := NewExpose("localhost", "ns/gw")
	url, err := e.URL("3f7b8e88-1a8c-4429-9615-c83886e7347b", "web")
	if err != nil || url != "http://web-3f7b8e88.localhost" {
		t.Errorf("url: %q %v", url, err)
	}
	// 54 characters is the longest: with "-" and 8 more it fills a 63-character DNS label exactly.
	long := strings.Repeat("x", MaxPublicName)
	host, err := e.Host("3f7b8e88-1a8c-4429-9615-c83886e7347b", long)
	if err != nil || len(strings.SplitN(host, ".", 2)[0]) != 63 {
		t.Errorf("the longest name should fit: %v len=%d", err, len(strings.SplitN(host, ".", 2)[0]))
	}
	if _, err := e.Host(app, long+"x"); err == nil {
		t.Error("one more character must be refused")
	}
}

func TestHTTPRoute(t *testing.T) {
	e, _ := NewExpose("apps.example.com", "nimbus-gateway/nimbus")
	r, err := HTTPRoute(app, "dep-1", Container{Name: "web", Port: port(8080)}, e)
	if err != nil {
		t.Fatal(err)
	}
	if r.GetName() != "web" || r.GetNamespace() != "nimbus-app-"+app || r.GetKind() != "HTTPRoute" || r.GetAPIVersion() != "gateway.networking.k8s.io/v1" {
		t.Errorf("identity: %s %s/%s", r.GetKind(), r.GetNamespace(), r.GetName())
	}
	hosts, _, _ := unstructured.NestedStringSlice(r.Object, "spec", "hostnames")
	if len(hosts) != 1 || hosts[0] != "web-0f1e2d3c.apps.example.com" {
		t.Errorf("hostnames: %v", hosts)
	}
	parents, _, _ := unstructured.NestedSlice(r.Object, "spec", "parentRefs")
	p := parents[0].(map[string]any)
	if p["name"] != "nimbus" || p["namespace"] != "nimbus-gateway" || p["kind"] != "Gateway" {
		t.Errorf("parent: %v", p)
	}
	rules, _, _ := unstructured.NestedSlice(r.Object, "spec", "rules")
	backend := rules[0].(map[string]any)["backendRefs"].([]any)[0].(map[string]any)
	if backend["name"] != "web" || backend["port"] != int64(8080) {
		t.Errorf("backend: %v", backend)
	}
	if r.GetLabels()["nimbus.dev/app"] != app || r.GetAnnotations()["nimbus.dev/deployment"] != "dep-1" {
		t.Errorf("labels/annotations: %v %v", r.GetLabels(), r.GetAnnotations())
	}
	// It has to survive the trip to the API server as JSON.
	if _, err := r.MarshalJSON(); err != nil {
		t.Error(err)
	}
	if _, err := HTTPRoute(app, "d", Container{Name: "web"}, e); err == nil {
		t.Error("a route needs a port to send traffic to")
	}
}

func route(parents ...map[string]any) *unstructured.Unstructured {
	list := []any{}
	for _, p := range parents {
		list = append(list, p)
	}
	return &unstructured.Unstructured{Object: map[string]any{"status": map[string]any{"parents": list}}}
}

func parent(conds ...[3]string) map[string]any { // each: type, status, reason+message joined by |
	list := []any{}
	for _, c := range conds {
		reason, msg, _ := strings.Cut(c[2], "|")
		list = append(list, map[string]any{"type": c[0], "status": c[1], "reason": reason, "message": msg})
	}
	return map[string]any{"conditions": list}
}

func TestRouteStatus(t *testing.T) {
	accepted := [3]string{"Accepted", "True", "Accepted|"}
	resolved := [3]string{"ResolvedRefs", "True", "ResolvedRefs|"}
	cases := []struct {
		name     string
		r        *unstructured.Unstructured
		ok       bool
		failWith string
	}{
		{"no status yet", &unstructured.Unstructured{Object: map[string]any{}}, false, ""},
		{"no parents yet", route(), false, ""},
		{"accepted and resolved", route(parent(accepted, resolved)), true, ""},
		{"accepted, backend not yet resolved", route(parent(accepted)), false, ""},
		{"refused", route(parent([3]string{"Accepted", "False", "NotAllowedByListeners|listener does not allow routes from this namespace"}, resolved)), false, "NotAllowedByListeners"},
		{"backend missing", route(parent(accepted, [3]string{"ResolvedRefs", "False", "BackendNotFound|Service web not found"})), false, "BackendNotFound"},
		{"two gateways, one still pending", route(parent(accepted, resolved), parent(accepted)), false, ""},
		{"two gateways, both fine", route(parent(accepted, resolved), parent(accepted, resolved)), true, ""},
	}
	for _, tc := range cases {
		ok, fail := routeStatus(tc.r)
		if ok != tc.ok || (tc.failWith == "") != (fail == "") || !strings.Contains(fail, tc.failWith) {
			t.Errorf("%s: ok=%v fail=%q", tc.name, ok, fail)
		}
	}
	_, fail := routeStatus(route(parent([3]string{"Accepted", "False", "NotAllowedByListeners|listener does not allow routes from this namespace"})))
	if !strings.Contains(fail, "does not allow routes") {
		t.Errorf("the reason's message should come through: %q", fail)
	}
}

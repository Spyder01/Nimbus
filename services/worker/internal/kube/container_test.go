package kube

import (
	"errors"
	"strings"
	"testing"
)

func TestParseContainer(t *testing.T) {
	c, err := ParseContainer([]byte(`{"name":"api","image":"nginx:1.27","port":80,"expose":false,"kind":"stateless","replicas":2,
		"minReplicas":null,"maxReplicas":null,"cpuTarget":null,"env":[{"key":"A","value":"1"},{"key":"B","value":""}],"volume":null}`))
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "api" || c.Image != "nginx:1.27" || *c.Port != 80 || c.Replicas != 2 || len(c.Env) != 2 || c.Autoscaled() {
		t.Errorf("parsed wrongly: %+v", c)
	}

	// No replicas given means one.
	c, err = ParseContainer([]byte(`{"name":"a","image":"x","kind":"stateless"}`))
	if err != nil || c.Replicas != 1 || c.Port != nil {
		t.Errorf("defaults: %+v %v", c, err)
	}
}

func TestParseContainerRefusesWhatItCannotDeployYet(t *testing.T) {
	cases := map[string]struct{ json, mention string }{
		"replicated stateful": {`{"name":"db","image":"x","kind":"stateful","replicas":2,"volume":{"size":"1Gi","mountPath":"/d"}}`, "more than one replica"},
		"a secret":            {`{"name":"a","image":"x","kind":"stateless","env":[{"key":"A","value":"1"},{"key":"PASSWORD","secret":true}]}`, "PASSWORD"},
	}
	for name, tc := range cases {
		_, err := ParseContainer([]byte(tc.json))
		if !errors.Is(err, ErrNotSupported) || !strings.Contains(err.Error(), tc.mention) {
			t.Errorf("%s: got %v", name, err)
		}
	}
	for name, raw := range map[string]string{"no image": `{"name":"a","image":" "}`, "no name": `{"image":"x"}`, "not json": `nope`} {
		if _, err := ParseContainer([]byte(raw)); err == nil || errors.Is(err, ErrNotSupported) {
			t.Errorf("%s: expected an ordinary error, got %v", name, err)
		}
	}
}

func TestParseStatefulContainer(t *testing.T) {
	c, err := ParseContainer([]byte(`{"name":"db","image":"postgres:16","kind":"stateful","replicas":1,"volume":{"size":"2Gi","mountPath":"/var/lib/postgresql/data"}}`))
	if err != nil || !c.Stateful() || c.Volume.Size != "2Gi" {
		t.Fatalf("%+v %v", c, err)
	}
	// Mistakes in the volume are ordinary errors, not "not supported".
	for name, raw := range map[string]string{
		"no volume":           `{"name":"db","image":"x","kind":"stateful"}`,
		"volume on stateless": `{"name":"db","image":"x","kind":"stateless","volume":{"size":"1Gi","mountPath":"/d"}}`,
		"bad size":            `{"name":"db","image":"x","kind":"stateful","volume":{"size":"lots","mountPath":"/d"}}`,
		"relative path":       `{"name":"db","image":"x","kind":"stateful","volume":{"size":"1Gi","mountPath":"data"}}`,
	} {
		if _, err := ParseContainer([]byte(raw)); err == nil || errors.Is(err, ErrNotSupported) {
			t.Errorf("%s: got %v", name, err)
		}
	}
}

func TestAutoscaledOnlyWhenAskedFor(t *testing.T) {
	one := 1
	for _, c := range []Container{{Kind: "stateless", MinReplicas: &one}, {Kind: "stateless", MaxReplicas: &one}, {Kind: "stateless", CPUTarget: &one}} {
		if !c.Autoscaled() {
			t.Errorf("%+v should be autoscaled", c)
		}
	}
	if (Container{Kind: "stateless"}).Autoscaled() || (Container{Kind: "stateful", MinReplicas: &one}).Autoscaled() {
		t.Error("autoscaled without being asked, or a stateful one")
	}
}

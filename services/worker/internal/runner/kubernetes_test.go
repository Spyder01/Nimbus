package runner

import (
	"context"
	"log/slog"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	dynamicfake "k8s.io/client-go/dynamic/fake"
	"k8s.io/client-go/kubernetes/fake"

	"nimbus/worker/internal/jobs"
	"nimbus/worker/internal/kube"
)

const appID = "0f1e2d3c-1111-2222-3333-444455556666"

// A cluster with one deployment, one service and nothing else, for the policy on what a stop may undo.
func setup(t *testing.T) (*Kubernetes, *cluster) {
	t.Helper()
	ns := kube.NamespaceFor(appID)
	route := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": "gateway.networking.k8s.io/v1", "kind": "HTTPRoute",
		"metadata": map[string]any{"name": "api", "namespace": ns},
	}}
	c := &cluster{
		core: fake.NewSimpleClientset(
			&appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: "api", Namespace: ns}},
			&corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: "api", Namespace: ns}},
		),
		dyn: dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(),
			map[schema.GroupVersionResource]string{{Group: "gateway.networking.k8s.io", Version: "v1", Resource: "httproutes"}: "HTTPRouteList"}, route),
	}
	return &Kubernetes{deployer: kube.NewDeployer(c.core, c.dyn)}, c
}

type cluster struct {
	core *fake.Clientset
	dyn  *dynamicfake.FakeDynamicClient
}

// present says whether the container's deployment, service and public route exist.
func present(t *testing.T, c *cluster) (deployment, service, route bool) {
	t.Helper()
	ns := kube.NamespaceFor(appID)
	d, _ := c.core.AppsV1().Deployments(ns).List(context.Background(), metav1.ListOptions{})
	s, _ := c.core.CoreV1().Services(ns).List(context.Background(), metav1.ListOptions{})
	r, _ := c.dyn.Resource(schema.GroupVersionResource{Group: "gateway.networking.k8s.io", Version: "v1", Resource: "httproutes"}).Namespace(ns).List(context.Background(), metav1.ListOptions{})
	return len(d.Items) == 1, len(s.Items) == 1, len(r.Items) == 1
}

// ctx cancelled for a reason, as the worker does it.
func stoppedFor(cause error) context.Context {
	ctx, cancel := context.WithCancelCause(context.Background())
	cancel(cause)
	return ctx
}

func TestAStoppedFirstDeployIsRemoved(t *testing.T) {
	k, cs := setup(t)
	err := k.stopped(stoppedFor(ErrStopped), slog.Default(), jobs.Job{AppID: appID, Name: "api"}, true, context.Canceled)
	if err != context.Canceled {
		t.Errorf("the original error should come back unchanged, got %v", err)
	}
	if d, s, r := present(t, cs); d || s || r {
		t.Errorf("what the cancelled first deploy created should be gone: deployment=%v service=%v route=%v", d, s, r)
	}
}

func TestNothingIsRemovedUnlessItWasAUserCancelOfAFirstDeploy(t *testing.T) {
	cases := map[string]struct {
		ctx     context.Context
		created bool
	}{
		"a cancelled redeploy (the container was already there)": {stoppedFor(ErrStopped), false},
		"a lost lease (someone else may be deploying it now)":    {stoppedFor(context.DeadlineExceeded), true},
		"a worker shutting down":                                 {stoppedFor(context.Canceled), true},
		"a cancel with no reason given":                          {stoppedFor(nil), true},
		"not stopped at all (the deploy itself failed)":          {context.Background(), true},
	}
	for name, tc := range cases {
		k, cs := setup(t)
		_ = k.stopped(tc.ctx, slog.Default(), jobs.Job{AppID: appID, Name: "api"}, tc.created, context.Canceled)
		if d, s, r := present(t, cs); !d || !s || !r {
			t.Errorf("%s: it must leave everything alone (deployment=%v service=%v route=%v)", name, d, s, r)
		}
	}
}

// Stopping a first deploy of a stateful container removes it but leaves its data.
func TestAStoppedStatefulDeployKeepsItsVolume(t *testing.T) {
	ns := kube.NamespaceFor(appID)
	core := fake.NewSimpleClientset(
		&appsv1.StatefulSet{ObjectMeta: metav1.ObjectMeta{Name: "db", Namespace: ns}},
		&corev1.PersistentVolumeClaim{ObjectMeta: metav1.ObjectMeta{Name: "data-db-0", Namespace: ns}},
	)
	dyn := dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(),
		map[schema.GroupVersionResource]string{{Group: "gateway.networking.k8s.io", Version: "v1", Resource: "httproutes"}: "HTTPRouteList"})
	k := &Kubernetes{deployer: kube.NewDeployer(core, dyn)}

	err := k.stopped(stoppedFor(ErrStopped), slog.Default(), jobs.Job{AppID: appID, Name: "db"}, true, context.Canceled)
	_ = err
	sts, _ := core.AppsV1().StatefulSets(ns).List(context.Background(), metav1.ListOptions{})
	pvc, _ := core.CoreV1().PersistentVolumeClaims(ns).List(context.Background(), metav1.ListOptions{})
	if len(sts.Items) != 0 || len(pvc.Items) != 1 {
		t.Errorf("statefulsets=%d claims=%d, want 0 and 1", len(sts.Items), len(pvc.Items))
	}
}

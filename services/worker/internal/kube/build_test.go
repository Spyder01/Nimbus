package kube

import (
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const app = "0f1e2d3c-1111-2222-3333-444455556666"

func port(n int) *int      { return &n }
func str(s string) *string { return &s }

func TestDeployment(t *testing.T) {
	c := Container{Name: "api", Image: "nginx:1.27", Port: port(80), Kind: "stateless", Replicas: 3,
		Env: []EnvVar{{Key: "MODE", Value: str("prod")}, {Key: "EMPTY"}}}
	d := Deployment(app, "dep-1", c, 300)

	if d.Name != "api" || d.Namespace != "nimbus-app-"+app || d.TypeMeta.Kind != "Deployment" {
		t.Errorf("identity: %s/%s %s", d.Namespace, d.Name, d.TypeMeta.Kind)
	}
	if d.Spec.Replicas == nil || *d.Spec.Replicas != 3 {
		t.Errorf("replicas: %v", d.Spec.Replicas)
	}
	if *d.Spec.ProgressDeadlineSeconds != 300 {
		t.Errorf("progress deadline: %d", *d.Spec.ProgressDeadlineSeconds)
	}
	pod := d.Spec.Template.Spec
	if *pod.AutomountServiceAccountToken {
		t.Error("the app's pods must not get a Kubernetes API token")
	}
	k := pod.Containers[0]
	if k.Image != "nginx:1.27" || k.Ports[0].ContainerPort != 80 || k.ReadinessProbe == nil || k.ReadinessProbe.TCPSocket.Port.IntVal != 80 {
		t.Errorf("container: %+v", k)
	}
	if k.Resources.Requests.Cpu().String() != "50m" {
		t.Errorf("cpu request: %s (autoscaling needs one)", k.Resources.Requests.Cpu())
	}
	if len(k.Env) != 2 || k.Env[0].Name != "MODE" || k.Env[0].Value != "prod" || k.Env[1].Value != "" {
		t.Errorf("env: %+v", k.Env)
	}
	if d.Spec.MinReadySeconds != 5 {
		t.Errorf("a container with a port must stay up a few seconds to count as ready, got %d", d.Spec.MinReadySeconds)
	}
	if d.Annotations["nimbus.dev/deployment"] != "dep-1" {
		t.Error("the deployment id should be an annotation")
	}
}

func TestSelectorNeverChangesAndPodsDontCarryTheDeploymentID(t *testing.T) {
	c := Container{Name: "api", Image: "x", Port: port(80), Kind: "stateless", Replicas: 1}
	a, b := Deployment(app, "dep-1", c, 60), Deployment(app, "dep-2", c, 60)
	sa, sb := a.Spec.Selector.MatchLabels, b.Spec.Selector.MatchLabels
	if len(sa) != 2 || sa["app.kubernetes.io/name"] != "api" || sa["nimbus.dev/app"] != app || sa["app.kubernetes.io/name"] != sb["app.kubernetes.io/name"] {
		t.Errorf("selector: %v", sa)
	}
	// A different deployment id must not change the pod template, or every redeploy would restart everything.
	for k, v := range a.Spec.Template.Labels {
		if b.Spec.Template.Labels[k] != v {
			t.Errorf("pod label %s differs between deployments", k)
		}
	}
	if len(a.Spec.Template.Annotations) != 0 {
		t.Errorf("pod annotations: %v", a.Spec.Template.Annotations)
	}
}

func TestNoPortMeansNoProbeAndNoService(t *testing.T) {
	c := Container{Name: "worker", Image: "x", Kind: "stateless", Replicas: 1}
	d := Deployment(app, "d", c, 60)
	if d.Spec.Template.Spec.Containers[0].ReadinessProbe != nil || len(d.Spec.Template.Spec.Containers[0].Ports) != 0 {
		t.Error("nothing to probe without a port")
	}
	if d.Spec.MinReadySeconds != 10 {
		t.Errorf("with nothing to probe, staying up is the only evidence it works: want a longer wait, got %d", d.Spec.MinReadySeconds)
	}
	if Service(app, c) != nil {
		t.Error("a container with no port gets no service")
	}
}

func TestService(t *testing.T) {
	s := Service(app, Container{Name: "api", Port: port(8080)})
	if s.Name != "api" || s.Namespace != "nimbus-app-"+app || s.Spec.Ports[0].Port != 8080 || s.Spec.Ports[0].TargetPort.IntVal != 8080 {
		t.Errorf("service: %+v", s.Spec)
	}
	if s.Spec.Selector["app.kubernetes.io/name"] != "api" || s.Spec.Type != "" {
		t.Errorf("selector/type: %+v", s.Spec)
	}
}

func TestAutoscalingLeavesTheReplicaCountToTheAutoscaler(t *testing.T) {
	min, max, cpu := 2, 6, 60
	c := Container{Name: "api", Image: "x", Port: port(80), Kind: "stateless", Replicas: 2, MinReplicas: &min, MaxReplicas: &max, CPUTarget: &cpu}
	if d := Deployment(app, "d", c, 60); d.Spec.Replicas != nil {
		t.Errorf("replicas must be left out so applying again doesn't undo scaling, got %d", *d.Spec.Replicas)
	}
	h := Autoscaler(app, c)
	if h == nil || *h.Spec.MinReplicas != 2 || h.Spec.MaxReplicas != 6 || *h.Spec.Metrics[0].Resource.Target.AverageUtilization != 60 || h.Spec.ScaleTargetRef.Name != "api" {
		t.Errorf("autoscaler: %+v", h)
	}

	// Defaults for what the design leaves out: 1 up to the replica count (or the min), at 70% CPU.
	c2 := Container{Name: "api", Image: "x", Kind: "stateless", Replicas: 4, CPUTarget: &cpu}
	h2 := Autoscaler(app, c2)
	if *h2.Spec.MinReplicas != 1 || h2.Spec.MaxReplicas != 4 {
		t.Errorf("defaults: min %d max %d", *h2.Spec.MinReplicas, h2.Spec.MaxReplicas)
	}
	if h3 := Autoscaler(app, Container{Name: "a", Kind: "stateless", Replicas: 1, MinReplicas: &min}); h3 == nil || h3.Spec.MaxReplicas < 2 {
		t.Errorf("max must not be below min: %+v", h3)
	}
	if Autoscaler(app, Container{Name: "a", Kind: "stateless", Replicas: 3}) != nil {
		t.Error("no autoscaler unless asked for")
	}
}

func TestNamespaceIsLabelled(t *testing.T) {
	n := Namespace(app)
	if n.Name != "nimbus-app-"+app || n.Labels["nimbus.dev/app"] != app || n.Labels["app.kubernetes.io/managed-by"] != "nimbus" {
		t.Errorf("namespace: %+v", n.ObjectMeta)
	}
	if len(n.Name) > 63 {
		t.Errorf("namespace name too long: %d", len(n.Name))
	}
}

func statefulContainer() Container {
	port := 5432
	return Container{Name: "db", Image: "postgres:16", Kind: "stateful", Replicas: 1, Port: &port,
		Volume: &Volume{Size: "2Gi", MountPath: "/var/lib/postgresql/data"}}
}

func TestStatefulSet(t *testing.T) {
	c := statefulContainer()
	s := StatefulSet(app, "dep-1", c)

	if s.Namespace != NamespaceFor(app) || *s.Spec.Replicas != 1 || s.Spec.ServiceName != "db" {
		t.Errorf("wrong shape: %+v", s.Spec)
	}
	if len(s.Spec.VolumeClaimTemplates) != 1 {
		t.Fatalf("claims: %+v", s.Spec.VolumeClaimTemplates)
	}
	claim := s.Spec.VolumeClaimTemplates[0]
	if q := claim.Spec.Resources.Requests["storage"]; q.String() != "2Gi" {
		t.Errorf("size = %s", q.String())
	}
	mounts := s.Spec.Template.Spec.Containers[0].VolumeMounts
	if len(mounts) != 1 || mounts[0].Name != claim.Name || mounts[0].MountPath != "/var/lib/postgresql/data" {
		t.Errorf("mounts %+v do not match claim %q", mounts, claim.Name)
	}
	// The data must outlive the container, whether it is removed or scaled down.
	r := s.Spec.PersistentVolumeClaimRetentionPolicy
	if r == nil || r.WhenDeleted != "Retain" || r.WhenScaled != "Retain" {
		t.Errorf("retention policy: %+v", r)
	}
	// Same pods, ports and probe as a stateless one, and the same selector.
	d := Deployment(app, "dep-1", Container{Name: "db", Image: "postgres:16", Kind: "stateless", Replicas: 1, Port: c.Port}, 60)
	if len(s.Spec.Template.Spec.Containers[0].Ports) != 1 || s.Spec.Template.Spec.Containers[0].ReadinessProbe == nil ||
		s.Spec.Selector.String() != d.Spec.Selector.String() {
		t.Error("pod differs from a stateless container's")
	}
}

func TestStatefulStatus(t *testing.T) {
	one := int32(1)
	sts := func(gen, observed int64, updated, available int32) *appsv1.StatefulSet {
		return &appsv1.StatefulSet{
			ObjectMeta: metav1.ObjectMeta{Generation: gen},
			Spec:       appsv1.StatefulSetSpec{Replicas: &one},
			Status:     appsv1.StatefulSetStatus{ObservedGeneration: observed, UpdatedReplicas: updated, AvailableReplicas: available},
		}
	}
	for name, tc := range map[string]struct {
		s    *appsv1.StatefulSet
		done bool
	}{
		"not observed yet":  {sts(2, 1, 1, 1), false},
		"not updated":       {sts(1, 1, 0, 0), false},
		"not yet available": {sts(1, 1, 1, 0), false},
		"done":              {sts(1, 1, 1, 1), true},
	} {
		if done, failed, _ := statefulStatus(tc.s); done != tc.done || failed != "" {
			t.Errorf("%s: done=%v failed=%q", name, done, failed)
		}
	}
}

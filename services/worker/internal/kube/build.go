package kube

import (
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	autoscalingv2 "k8s.io/api/autoscaling/v2"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
)

const (
	// FieldManager owns the fields the worker sets, so applying again changes only what the design says.
	FieldManager = "nimbus-worker"

	labelManagedBy   = "app.kubernetes.io/managed-by"
	labelName        = "app.kubernetes.io/name"
	labelApp         = "nimbus.dev/app"
	annotationDeploy = "nimbus.dev/deployment"
)

// NamespaceFor is where an app's containers live.
func NamespaceFor(appID string) string { return "nimbus-app-" + appID }

// selector picks an app's pods of one container. It never changes, because a Deployment's selector can't.
func selector(appID, name string) map[string]string {
	return map[string]string{labelName: name, labelApp: appID}
}

func labels(appID, name string) map[string]string {
	l := selector(appID, name)
	l[labelManagedBy] = "nimbus"
	return l
}

func Namespace(appID string) *corev1.Namespace {
	return &corev1.Namespace{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Namespace"},
		ObjectMeta: metav1.ObjectMeta{Name: NamespaceFor(appID), Labels: map[string]string{labelManagedBy: "nimbus", labelApp: appID}},
	}
}

// Deployment runs a stateless container. With autoscaling the replica count belongs to the autoscaler, so it is left
// out and re-applying a design never fights it.
func Deployment(appID, deploymentID string, c Container, timeoutSeconds int32) *appsv1.Deployment {
	container := corev1.Container{
		Name:  c.Name,
		Image: c.Image,
		// Requests (no limits): the scheduler needs them, and autoscaling on CPU is measured against the request.
		Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
			corev1.ResourceCPU:    resource.MustParse("50m"),
			corev1.ResourceMemory: resource.MustParse("64Mi"),
		}},
	}
	for _, e := range c.Env {
		v := ""
		if e.Value != nil {
			v = *e.Value
		}
		container.Env = append(container.Env, corev1.EnvVar{Name: e.Key, Value: v})
	}
	if c.Port != nil {
		container.Ports = []corev1.ContainerPort{{Name: "http", ContainerPort: int32(*c.Port)}}
		// Ready once it accepts connections: all that can be known about an image nothing is known about.
		container.ReadinessProbe = &corev1.Probe{
			ProbeHandler:        corev1.ProbeHandler{TCPSocket: &corev1.TCPSocketAction{Port: intstr.FromInt32(int32(*c.Port))}},
			InitialDelaySeconds: 2,
			PeriodSeconds:       5,
		}
	}

	// A pod only counts as available after staying up this long. Without it a container that starts and then crashes
	// right away (and one with no port, so nothing to probe) would be taken for ready the moment it started.
	minReady := int32(10)
	if c.Port != nil {
		minReady = 5 // it also has to be accepting connections
	}

	d := &appsv1.Deployment{
		TypeMeta: metav1.TypeMeta{APIVersion: "apps/v1", Kind: "Deployment"},
		ObjectMeta: metav1.ObjectMeta{
			Name: c.Name, Namespace: NamespaceFor(appID), Labels: labels(appID, c.Name),
			// Not on the pods: a new deployment id there would restart them even when nothing changed.
			Annotations: map[string]string{annotationDeploy: deploymentID},
		},
		Spec: appsv1.DeploymentSpec{
			Selector:                &metav1.LabelSelector{MatchLabels: selector(appID, c.Name)},
			ProgressDeadlineSeconds: &timeoutSeconds,
			MinReadySeconds:         minReady,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels(appID, c.Name)},
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{container},
					// The app's containers have no business talking to the Kubernetes API.
					AutomountServiceAccountToken: ptr(false),
				},
			},
		},
	}
	if !c.Autoscaled() {
		d.Spec.Replicas = ptr(int32(c.Replicas))
	}
	return d
}

// Service makes the container reachable inside the app by its name, e.g. http://api:8080. Nil if it has no port.
func Service(appID string, c Container) *corev1.Service {
	if c.Port == nil {
		return nil
	}
	return &corev1.Service{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Service"},
		ObjectMeta: metav1.ObjectMeta{Name: c.Name, Namespace: NamespaceFor(appID), Labels: labels(appID, c.Name)},
		Spec: corev1.ServiceSpec{
			Selector: selector(appID, c.Name),
			Ports:    []corev1.ServicePort{{Name: "http", Port: int32(*c.Port), TargetPort: intstr.FromInt32(int32(*c.Port))}},
		},
	}
}

// Autoscaler follows CPU use between the design's minimum and maximum. Nil unless the design asks for autoscaling.
func Autoscaler(appID string, c Container) *autoscalingv2.HorizontalPodAutoscaler {
	if !c.Autoscaled() {
		return nil
	}
	min := 1
	if c.MinReplicas != nil {
		min = *c.MinReplicas
	}
	max := max(min, c.Replicas)
	if c.MaxReplicas != nil {
		max = *c.MaxReplicas
	}
	cpu := 70
	if c.CPUTarget != nil {
		cpu = *c.CPUTarget
	}
	return &autoscalingv2.HorizontalPodAutoscaler{
		TypeMeta:   metav1.TypeMeta{APIVersion: "autoscaling/v2", Kind: "HorizontalPodAutoscaler"},
		ObjectMeta: metav1.ObjectMeta{Name: c.Name, Namespace: NamespaceFor(appID), Labels: labels(appID, c.Name)},
		Spec: autoscalingv2.HorizontalPodAutoscalerSpec{
			ScaleTargetRef: autoscalingv2.CrossVersionObjectReference{APIVersion: "apps/v1", Kind: "Deployment", Name: c.Name},
			MinReplicas:    ptr(int32(min)),
			MaxReplicas:    int32(max),
			Metrics: []autoscalingv2.MetricSpec{{
				Type: autoscalingv2.ResourceMetricSourceType,
				Resource: &autoscalingv2.ResourceMetricSource{
					Name:   corev1.ResourceCPU,
					Target: autoscalingv2.MetricTarget{Type: autoscalingv2.UtilizationMetricType, AverageUtilization: ptr(int32(cpu))},
				},
			}},
		},
	}
}

func ptr[T any](v T) *T { return &v }

func describe(c Container) string { return fmt.Sprintf("%s (%s)", c.Name, c.Image) }

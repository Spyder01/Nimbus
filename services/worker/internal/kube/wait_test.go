package kube

import (
	"strings"
	"testing"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
)

func dep(gen, observed int64, want, updated, total, available int32, conds ...appsv1.DeploymentCondition) *appsv1.Deployment {
	d := &appsv1.Deployment{}
	d.Generation = gen
	d.Spec.Replicas = &want
	d.Status = appsv1.DeploymentStatus{ObservedGeneration: observed, UpdatedReplicas: updated, Replicas: total, AvailableReplicas: available, Conditions: conds}
	return d
}

func TestRolloutStatus(t *testing.T) {
	exceeded := appsv1.DeploymentCondition{Type: appsv1.DeploymentProgressing, Reason: "ProgressDeadlineExceeded", Message: "ReplicaSet has timed out"}
	cases := []struct {
		name            string
		d               *appsv1.Deployment
		done            bool
		failed, waiting string
	}{
		{"done", dep(1, 1, 2, 2, 2, 2), true, "", ""},
		{"not noticed yet", dep(2, 1, 2, 2, 2, 2), false, "", "picked up"},
		{"updating", dep(1, 1, 3, 1, 1, 1), false, "", "1 of 3 replicas updated"},
		{"old ones stopping", dep(1, 1, 2, 2, 3, 2), false, "", "old replicas"},
		{"not available yet", dep(1, 1, 2, 2, 2, 1), false, "", "1 of 2 replicas available"},
		{"deadline", dep(1, 1, 2, 2, 2, 0, exceeded), false, "did not become ready in time", ""},
	}
	for _, tc := range cases {
		done, failed, waiting := rolloutStatus(tc.d)
		if done != tc.done || !strings.Contains(failed, tc.failed) || !strings.Contains(waiting, tc.waiting) || (tc.failed == "") != (failed == "") {
			t.Errorf("%s: done=%v failed=%q waiting=%q", tc.name, done, failed, waiting)
		}
	}
	// No replica count at all means one.
	one := &appsv1.Deployment{}
	one.Status = appsv1.DeploymentStatus{UpdatedReplicas: 1, Replicas: 1, AvailableReplicas: 1}
	if done, _, _ := rolloutStatus(one); !done {
		t.Error("a deployment with the default single replica should be done when it has one available")
	}
}

func waiting(name, reason, message string) corev1.Pod {
	return corev1.Pod{
		ObjectMeta: metaName(name),
		Status:     corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{State: corev1.ContainerState{Waiting: &corev1.ContainerStateWaiting{Reason: reason, Message: message}}}}},
	}
}

func TestPodProblemWaitsBeforeGivingUp(t *testing.T) {
	t0 := time.Now()
	seen := map[string]time.Time{}
	pull := []corev1.Pod{waiting("p1", "ImagePullBackOff", `Back-off pulling image "nope:1"`)}

	if got := podProblem(pull, seen, t0); got != nil {
		t.Errorf("a first sighting is not a verdict: %q", got.message)
	}
	if got := podProblem(pull, seen, t0.Add(10*time.Second)); got != nil {
		t.Errorf("still inside the grace period: %q", got.message)
	}
	got := podProblem(pull, seen, t0.Add(16*time.Second))
	if got == nil || !strings.Contains(got.message, "ImagePullBackOff") || !strings.Contains(got.message, "nope:1") || got.crashed {
		t.Errorf("after the grace period it should say what is wrong, got %+v", got)
	}
}

func TestPodProblemKindsAndRecovery(t *testing.T) {
	t0 := time.Now()
	// Crash loops are reported at once: the pod has already crashed more than once.
	if got := podProblem([]corev1.Pod{waiting("p", "CrashLoopBackOff", "back-off 20s restarting")}, map[string]time.Time{}, t0); got == nil || !strings.Contains(got.message, "CrashLoopBackOff") || !got.crashed {
		t.Errorf("crash loop: %+v", got)
	}
	// Things that aren't problems (starting up) never count.
	if got := podProblem([]corev1.Pod{waiting("p", "ContainerCreating", "")}, map[string]time.Time{}, t0.Add(time.Hour)); got != nil {
		t.Errorf("ContainerCreating is normal: %q", got.message)
	}
	// A pod that can't be placed.
	unsched := corev1.Pod{ObjectMeta: metaName("p"), Status: corev1.PodStatus{Conditions: []corev1.PodCondition{{
		Type: corev1.PodScheduled, Status: corev1.ConditionFalse, Reason: corev1.PodReasonUnschedulable, Message: "0/1 nodes: Insufficient cpu"}}}}
	seen := map[string]time.Time{}
	podProblem([]corev1.Pod{unsched}, seen, t0)
	if got := podProblem([]corev1.Pod{unsched}, seen, t0.Add(46*time.Second)); got == nil || !strings.Contains(got.message, "Insufficient cpu") {
		t.Errorf("unschedulable: %+v", got)
	}
	// A problem that goes away is forgotten, so coming back starts the clock again.
	seen = map[string]time.Time{}
	podProblem([]corev1.Pod{waiting("p", "ImagePullBackOff", "")}, seen, t0)
	podProblem(nil, seen, t0.Add(10*time.Second))
	if len(seen) != 0 {
		t.Errorf("not forgotten: %v", seen)
	}
	if got := podProblem([]corev1.Pod{waiting("p", "ImagePullBackOff", "")}, seen, t0.Add(20*time.Second)); got != nil {
		t.Errorf("the clock should have restarted, got %q", got.message)
	}
}

func exited(name string, restarts int32, code int32, reason string, running bool) corev1.Pod {
	cs := corev1.ContainerStatus{Name: "app", RestartCount: restarts}
	t := &corev1.ContainerStateTerminated{ExitCode: code, Reason: reason}
	if running { // between restarts the current state is waiting and the exit is in the last state
		cs.State.Waiting = &corev1.ContainerStateWaiting{Reason: "CrashLoopBackOffish"}
		cs.LastTerminationState.Terminated = t
	} else {
		cs.State.Terminated = t
	}
	return corev1.Pod{ObjectMeta: metaName(name), Status: corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{cs}}}
}

func TestAContainerThatKeepsExitingIsAProblemEvenWithExitCodeZero(t *testing.T) {
	t0 := time.Now()
	for name, pod := range map[string]corev1.Pod{
		"clean exit, now terminated": exited("p", 3, 0, "Completed", false),
		"error exit, between runs":   exited("p", 2, 1, "Error", true),
	} {
		got := podProblem([]corev1.Pod{pod}, map[string]time.Time{}, t0)
		if got == nil || !got.crashed || got.container != "app" || !strings.Contains(got.message, "keeps exiting") || !strings.Contains(got.message, "restarted") {
			t.Errorf("%s: %+v", name, got)
		}
	}
	// One restart is not a loop yet, and a container that is simply running is fine.
	if got := podProblem([]corev1.Pod{exited("p", 1, 0, "Completed", false)}, map[string]time.Time{}, t0); got != nil {
		t.Errorf("a single restart: %+v", got)
	}
	running := corev1.Pod{ObjectMeta: metaName("p"), Status: corev1.PodStatus{ContainerStatuses: []corev1.ContainerStatus{{Name: "app", RestartCount: 5, State: corev1.ContainerState{Running: &corev1.ContainerStateRunning{}}}}}}
	if got := podProblem([]corev1.Pod{running}, map[string]time.Time{}, t0); got != nil {
		t.Errorf("a running container is not a crash, however often it restarted before: %+v", got)
	}
}

func TestShortenKeepsMessagesReadable(t *testing.T) {
	if got := shorten("  one line  ", 50); got != "one line" {
		t.Errorf("%q", got)
	}
	if got := shorten("first\nsecond", 50); got != "first" {
		t.Errorf("only the first line: %q", got)
	}
	long := strings.Repeat("x", 500)
	if got := shorten(long, 100); len(got) > 102 || !strings.HasSuffix(got, "…") { // … is three bytes
		t.Errorf("cut to length: %d %q", len(got), got[len(got)-5:])
	}
}

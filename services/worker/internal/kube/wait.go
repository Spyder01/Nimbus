package kube

import (
	"context"
	"fmt"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const pollEvery = 2 * time.Second

// How long a problem has to last before it is taken to be permanent. Pulling an image fails once and is retried, and a
// pod may wait a moment for room, so neither counts on first sight.
var grace = map[string]time.Duration{
	"ErrImagePull":               30 * time.Second,
	"ImagePullBackOff":           15 * time.Second,
	"InvalidImageName":           3 * time.Second,
	"CreateContainerConfigError": 5 * time.Second,
	"CrashLoopBackOff":           0, // it has already crashed more than once
	"Restarting":                 0, // exited and restarted more than once, whatever the exit code
	"Unschedulable":              45 * time.Second,
}

// WaitReady returns nil once the Deployment has rolled out (all replicas updated and available), and an error saying
// what is wrong if it clearly can't, or doesn't within the timeout. It stops early, with ctx's error, if ctx ends.
func (d *Deployer) WaitReady(ctx context.Context, appID, name string, timeout time.Duration) error {
	ns := NamespaceFor(appID)
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(pollEvery)
	defer tick.Stop()
	firstSeen := map[string]time.Time{}
	last := "waiting to start"

	for {
		dep, err := d.cs.AppsV1().Deployments(ns).Get(ctx, name, metav1.GetOptions{})
		switch {
		case err != nil && ctx.Err() != nil:
			return ctx.Err()
		case err != nil:
			last = "can't read the deployment: " + err.Error()
		default:
			done, failed, status := rolloutStatus(dep)
			if done {
				return nil
			}
			if failed != "" {
				return fmt.Errorf("%s", failed)
			}
			last = status
			pods, err := d.cs.CoreV1().Pods(ns).List(ctx, metav1.ListOptions{LabelSelector: fmt.Sprintf("%s=%s,%s=%s", labelName, name, labelApp, appID)})
			if err == nil {
				if p := podProblem(pods.Items, firstSeen, time.Now()); p != nil {
					return fmt.Errorf("%s%s", p.message, d.lastOutput(ctx, ns, p))
				}
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return fmt.Errorf("not ready after %s: %s", timeout.Round(time.Second), last)
		case <-tick.C:
		}
	}
}

// lastOutput is the end of what a crashed container printed, as text to add to an error ("" if there is none).
func (d *Deployer) lastOutput(ctx context.Context, ns string, p *problem) string {
	if !p.crashed {
		return ""
	}
	tail := int64(8)
	raw, err := d.cs.CoreV1().Pods(ns).GetLogs(p.pod, &corev1.PodLogOptions{Container: p.container, Previous: true, TailLines: &tail}).DoRaw(ctx)
	text := strings.TrimSpace(string(raw))
	// When the container printed nothing, or the old container's log is gone, Kubernetes answers with a sentence about
	// that instead of a log: not worth passing on.
	if err != nil || text == "" || strings.HasPrefix(text, "unable to retrieve container logs") {
		return ""
	}
	if len(text) > 600 {
		text = "…" + text[len(text)-600:]
	}
	return "\nlast output:\n" + text
}

// rolloutStatus is `kubectl rollout status` for a Deployment: finished, failed (with why), or still going.
func rolloutStatus(d *appsv1.Deployment) (done bool, failed, status string) {
	if d.Generation > d.Status.ObservedGeneration {
		return false, "", "waiting for the change to be picked up"
	}
	for _, c := range d.Status.Conditions {
		if c.Type == appsv1.DeploymentProgressing && c.Reason == "ProgressDeadlineExceeded" {
			return false, "did not become ready in time: " + c.Message, ""
		}
	}
	want := int32(1)
	if d.Spec.Replicas != nil {
		want = *d.Spec.Replicas
	}
	switch {
	case d.Status.UpdatedReplicas < want:
		return false, "", fmt.Sprintf("%d of %d replicas updated", d.Status.UpdatedReplicas, want)
	case d.Status.Replicas > d.Status.UpdatedReplicas:
		return false, "", fmt.Sprintf("%d old replicas are still stopping", d.Status.Replicas-d.Status.UpdatedReplicas)
	case d.Status.AvailableReplicas < d.Status.UpdatedReplicas:
		return false, "", fmt.Sprintf("%d of %d replicas available", d.Status.AvailableReplicas, d.Status.UpdatedReplicas)
	}
	return true, "", ""
}

// problem is something wrong with a pod that won't fix itself.
type problem struct {
	message   string
	pod       string
	container string
	// crashed is true when the container ran and exited, so its last output says why.
	crashed bool
}

// restartsBeforeCrashLoop is how many restarts it takes to call a container crash-looping.
const restartsBeforeCrashLoop = 2

// podProblem says what is wrong when a pod has been stuck in a way that won't fix itself; nil otherwise. firstSeen
// remembers when each problem was first noticed, across calls.
func podProblem(pods []corev1.Pod, firstSeen map[string]time.Time, now time.Time) *problem {
	seen := map[string]bool{}
	for _, p := range pods {
		var found []finding
		for _, cs := range p.Status.ContainerStatuses {
			if w := cs.State.Waiting; w != nil {
				if _, tracked := grace[w.Reason]; tracked {
					found = append(found, finding{reason: w.Reason, message: w.Message, container: cs.Name, crashed: w.Reason == "CrashLoopBackOff"})
				}
			}
			// A container that exits is restarted, and between restarts it shows as Completed or Error rather than as
			// waiting, so look at how often it has restarted too (a clean exit, code 0, is as much a failure here).
			if t := terminated(cs); t != nil && cs.RestartCount >= restartsBeforeCrashLoop {
				found = append(found, finding{
					reason:    "Restarting",
					message:   fmt.Sprintf("the container keeps exiting (exit code %d, %s; restarted %d times)", t.ExitCode, t.Reason, cs.RestartCount),
					container: cs.Name, crashed: true,
				})
			}
		}
		for _, c := range p.Status.Conditions {
			if c.Type == corev1.PodScheduled && c.Status == corev1.ConditionFalse && c.Reason == corev1.PodReasonUnschedulable {
				found = append(found, finding{reason: "Unschedulable", message: c.Message})
			}
		}
		for _, f := range found {
			key := p.Name + "/" + f.reason
			seen[key] = true
			if _, ok := firstSeen[key]; !ok {
				firstSeen[key] = now
			}
			if now.Sub(firstSeen[key]) >= grace[f.reason] {
				msg := f.reason
				if m := shorten(f.message, 240); m != "" {
					msg += ": " + m
				}
				return &problem{message: msg, pod: p.Name, container: f.container, crashed: f.crashed}
			}
		}
	}
	for k := range firstSeen { // a problem that went away starts over if it comes back
		if !seen[k] {
			delete(firstSeen, k)
		}
	}
	return nil
}

type finding struct {
	reason, message, container string
	crashed                    bool
}

// terminated is how the container last ended, if it is not running now.
func terminated(cs corev1.ContainerStatus) *corev1.ContainerStateTerminated {
	if cs.State.Terminated != nil {
		return cs.State.Terminated
	}
	if cs.State.Waiting != nil { // between restarts
		return cs.LastTerminationState.Terminated
	}
	return nil
}

// shorten keeps the first line of a message, cut to at most n characters: registries and kubelets can be very wordy.
func shorten(s string, n int) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	if len(s) > n {
		s = s[:n-1] + "…"
	}
	return s
}

package kube

import (
	"context"
	"encoding/json"
	"fmt"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
)

// Deployer puts containers into a cluster.
type Deployer struct {
	cs  kubernetes.Interface
	dyn dynamic.Interface
}

func NewDeployer(cs kubernetes.Interface, dyn dynamic.Interface) *Deployer {
	return &Deployer{cs: cs, dyn: dyn}
}

func patchOptions() metav1.PatchOptions {
	force := true
	return metav1.PatchOptions{FieldManager: FieldManager, Force: &force}
}

// Apply creates or updates everything the container needs, in a namespace of its own for the app. Server-side apply
// makes it safe to repeat: the same design changes nothing, and a changed design changes only what differs. It reports
// whether the container's Deployment is new, so a cancelled first deploy can be cleaned up.
func (d *Deployer) Apply(ctx context.Context, appID, deploymentID string, c Container, timeoutSeconds int32) (created bool, err error) {
	ns := NamespaceFor(appID)
	if _, err := d.cs.AppsV1().Deployments(ns).Get(ctx, c.Name, metav1.GetOptions{}); apierrors.IsNotFound(err) {
		created = true
	} else if err != nil {
		return false, fmt.Errorf("looking for %s: %w", describe(c), err)
	}

	patch := func(what string, obj any, do func(data []byte) error) error {
		data, err := json.Marshal(obj)
		if err != nil {
			return fmt.Errorf("encoding %s: %w", what, err)
		}
		if err := do(data); err != nil {
			return fmt.Errorf("applying %s: %w", what, err)
		}
		return nil
	}
	apply := types.ApplyPatchType

	if err := patch("namespace "+ns, Namespace(appID), func(b []byte) error {
		_, err := d.cs.CoreV1().Namespaces().Patch(ctx, ns, apply, b, patchOptions())
		return err
	}); err != nil {
		return created, err
	}
	if err := patch("the deployment", Deployment(appID, deploymentID, c, timeoutSeconds), func(b []byte) error {
		_, err := d.cs.AppsV1().Deployments(ns).Patch(ctx, c.Name, apply, b, patchOptions())
		return err
	}); err != nil {
		return created, err
	}
	if svc := Service(appID, c); svc != nil {
		if err := patch("the service", svc, func(b []byte) error {
			_, err := d.cs.CoreV1().Services(ns).Patch(ctx, c.Name, apply, b, patchOptions())
			return err
		}); err != nil {
			return created, err
		}
	}
	if hpa := Autoscaler(appID, c); hpa != nil {
		if err := patch("the autoscaler", hpa, func(b []byte) error {
			_, err := d.cs.AutoscalingV2().HorizontalPodAutoscalers(ns).Patch(ctx, c.Name, apply, b, patchOptions())
			return err
		}); err != nil {
			return created, err
		}
	}
	return created, nil
}

// Remove deletes what Apply made for a container (not the namespace, which belongs to the whole app). Missing is fine.
func (d *Deployer) Remove(ctx context.Context, appID, name string) error {
	ns := NamespaceFor(appID)
	var firstErr error
	keep := func(err error) {
		if err != nil && !apierrors.IsNotFound(err) && firstErr == nil {
			firstErr = err
		}
	}
	keep(d.DeleteRoute(ctx, appID, name))
	keep(d.cs.AutoscalingV2().HorizontalPodAutoscalers(ns).Delete(ctx, name, metav1.DeleteOptions{}))
	keep(d.cs.CoreV1().Services(ns).Delete(ctx, name, metav1.DeleteOptions{}))
	keep(d.cs.AppsV1().Deployments(ns).Delete(ctx, name, metav1.DeleteOptions{}))
	return firstErr
}

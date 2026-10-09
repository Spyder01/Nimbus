package kube

import (
	"errors"
	"fmt"
	"os"

	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
)

// Clients are the two ways the worker talks to the cluster: typed (Deployments, Services, ...) and dynamic (the Gateway
// API's HTTPRoute, whose CRDs may not be installed at all).
type Clients struct {
	Core    kubernetes.Interface
	Dynamic dynamic.Interface
}

// Connect creates the clients for the cluster the worker deploys to.
//
// Inside a cluster it uses the pod's own service account (WORKER_KUBE_IN_CLUSTER=true). Anywhere else it reads a
// kubeconfig, and WORKER_KUBE_CONTEXT is required: a kubeconfig often has several clusters, and "whichever is current"
// is how things get deployed to the wrong one.
func Connect() (*Clients, string, error) {
	var cfg *rest.Config
	var where string
	if os.Getenv("WORKER_KUBE_IN_CLUSTER") == "true" {
		c, err := rest.InClusterConfig()
		if err != nil {
			return nil, "", fmt.Errorf("WORKER_KUBE_IN_CLUSTER is set but this is not a pod: %w", err)
		}
		cfg, where = c, "in-cluster "+c.Host
	} else {
		context := os.Getenv("WORKER_KUBE_CONTEXT")
		if context == "" {
			return nil, "", errors.New("set WORKER_KUBE_CONTEXT to the cluster to deploy to (or WORKER_KUBE_IN_CLUSTER=true inside a cluster)")
		}
		rules := clientcmd.NewDefaultClientConfigLoadingRules()
		if path := os.Getenv("WORKER_KUBECONFIG"); path != "" {
			rules.ExplicitPath = path
		}
		c, err := clientcmd.NewNonInteractiveDeferredLoadingClientConfig(rules, &clientcmd.ConfigOverrides{CurrentContext: context}).ClientConfig()
		if err != nil {
			return nil, "", fmt.Errorf("kubeconfig context %q: %w", context, err)
		}
		cfg, where = c, fmt.Sprintf("context %s (%s)", context, c.Host)
	}
	cs, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return nil, "", fmt.Errorf("creating the Kubernetes client: %w", err)
	}
	dyn, err := dynamic.NewForConfig(cfg)
	if err != nil {
		return nil, "", fmt.Errorf("creating the Kubernetes dynamic client: %w", err)
	}
	// Fail now, not on the first job, if the cluster can't be reached.
	if _, err := cs.Discovery().ServerVersion(); err != nil {
		return nil, "", fmt.Errorf("reaching the cluster at %s: %w", where, err)
	}
	return &Clients{Core: cs, Dynamic: dyn}, where, nil
}

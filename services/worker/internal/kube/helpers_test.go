package kube

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

func metaName(n string) metav1.ObjectMeta { return metav1.ObjectMeta{Name: n} }

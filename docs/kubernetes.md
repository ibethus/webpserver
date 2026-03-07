---
layout: page
title: Kubernetes
permalink: /kubernetes/
nav_order: 6
---

# Kubernetes

A production-ready manifest is provided at `kubernetes/deployment.yaml`. Apply it, adjust the ConfigMap for your needs, and expose via Ingress.

---

## Quickstart

```bash
kubectl apply -f kubernetes/deployment.yaml
kubectl -n webpserver get pods
kubectl -n webpserver port-forward service/webpserver 8080:8080
```

---

## Storage and replicas

Default: `ReadWriteOnce` PVC (10Gi), 1 replica.

For multiple replicas, use a `ReadWriteMany` storage provider (NFS, CephFS, EFS) and increase `spec.replicas`. The in-memory index converges lazily across pods via disk fallback. See [Architecture](architecture.md).

---

## Exposing the service

Uncomment the `Ingress` block in `deployment.yaml` and set your hostname:

```yaml
spec:
  rules:
    - host: images.example.com
```

Set `nginx.ingress.kubernetes.io/proxy-body-size` to at least `MAX_SIZE_MB + 4MB`.

Alternatives: change `Service` type to `NodePort` or `LoadBalancer`.

---

## Resource sizing

Default: `256Mi` request, `512Mi` limit. For large images (> 4 megapixels) or high concurrency, increase to `1Gi`.

Check pod consumption :
```bash
kubectl -n webpserver top pods
```
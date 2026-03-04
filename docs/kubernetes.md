---
layout: page
title: Kubernetes
permalink: /kubernetes/
nav_order: 6
---

# Kubernetes

A production-ready Kubernetes manifest is provided at `kubernetes/deployment.yaml` in the repository. It includes a `Namespace`, `Deployment`, `PersistentVolumeClaim`, `ConfigMap`, `Secret` and `Service`, plus a commented-out `Ingress` example.

---

## Prerequisites

- A running Kubernetes cluster (1.27+)
- `kubectl` configured and pointing to your cluster
- A container registry credential if pulling from a private registry (not needed for `ghcr.io` public images)

---

## Quickstart

### 1. Edit the manifest

Open `kubernetes/deployment.yaml` and replace:

- `ibethus` with your GitHub username in the `image:` field.
- Optionally adjust `resources.requests` and `resources.limits`.
- Optionally uncomment and configure the `Ingress` section.

### 2. Create the API key secret

Replace the placeholder value with a strong secret before applying.

```bash
kubectl -n webpserver create namespace webpserver
kubectl -n webpserver create secret generic webpserver-secret \
  --from-literal=api-key="$(openssl rand -hex 32)"
```

Or edit the `Secret` block directly in `deployment.yaml` before applying.

### 3. Apply the manifest

```bash
kubectl apply -f kubernetes/deployment.yaml
```

Expected output:

```
namespace/webpserver created
secret/webpserver-secret created
configmap/webpserver-config created
persistentvolumeclaim/webpserver-pvc created
deployment.apps/webpserver created
service/webpserver created
```

### 4. Verify the deployment

```bash
kubectl -n webpserver get pods
# NAME                          READY   STATUS    RESTARTS   AGE
# webpserver-6d9f8b7c5-xk2mn    1/1     Running   0          45s

kubectl -n webpserver get svc
# NAME         TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE
# webpserver   ClusterIP   10.96.142.33    <none>        8080/TCP   45s
```

### 5. Test locally via port-forward

```bash
kubectl -n webpserver port-forward service/webpserver 8080:8080
# In another terminal:
curl http://localhost:8080/liveness
# {"status":"ok"}
```

---

## Configuration

All configuration is managed via the `ConfigMap` in the manifest. Edit the `data` section and apply:

```bash
kubectl apply -f kubernetes/deployment.yaml
kubectl -n webpserver rollout restart deployment/webpserver
```

See [Configuration](configuration.md) for the full variable reference.

---

## Storage

The manifest creates a `PersistentVolumeClaim` requesting 10Gi of storage. Adjust the `storage` value to your needs before applying.

### Single replica (recommended)

The default manifest uses `accessModes: ReadWriteOnce`. This is supported by all common storage providers (hostPath, local-path-provisioner, Longhorn, AWS EBS, GCP PD, etc.).

### Multiple replicas

To scale beyond 1 replica, you must:

1. Change `accessModes` to `ReadWriteMany` in the PVC. This requires a storage provider that supports RWX (NFS, CephFS, Longhorn with RWX enabled, AWS EFS, etc.).
2. Increase `spec.replicas` in the Deployment.

```yaml
replicas: 3
```

Multi-replica deployments operate with eventual consistency: the in-memory variant index on each pod converges lazily via disk-check on cache miss. There is no risk of data corruption (writes use atomic rename). See the [Architecture](architecture.md) page for details.

---

## Exposing the service

The `Service` is of type `ClusterIP` by default. To expose it externally:

### Option 1: Ingress (recommended)

Uncomment the `Ingress` block at the bottom of `kubernetes/deployment.yaml` and adapt it to your ingress controller:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: webpserver
  namespace: webpserver
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "20m"
spec:
  ingressClassName: nginx
  rules:
    - host: images.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: webpserver
                port:
                  name: http
  tls:
    - hosts:
        - images.example.com
      secretName: webpserver-tls
```

**Note:** Set `proxy-body-size` to at least `MAX_SIZE_MB + 4MB` to account for multipart overhead.

### Option 2: NodePort

Change the `Service` type:

```yaml
spec:
  type: NodePort
  ports:
    - port: 8080
      targetPort: http
      nodePort: 30080
```

### Option 3: LoadBalancer

Change the `Service` type to `LoadBalancer`. Supported on managed clusters (EKS, GKE, AKS).

---

## Health probes

The manifest configures both `livenessProbe` and `readinessProbe` on `GET /liveness`:

```yaml
livenessProbe:
  httpGet:
    path: /liveness
    port: http
  initialDelaySeconds: 10
  periodSeconds: 30
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /liveness
    port: http
  initialDelaySeconds: 5
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

The pod is removed from the service load balancer until the readiness probe passes. Container replacement on failure is handled by the liveness probe. The `/liveness` endpoint always returns 200 immediately — it does not check disk or JNI availability.

---

## Resource sizing

Default values in the manifest:

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "256Mi"
  limits:
    cpu: "1000m"
    memory: "512Mi"
```

webp4j allocates native memory buffers during encoding/decoding. For large images (> 4 megapixels) or high concurrency, increase the memory limit to `1Gi` or more. Monitor container memory usage with:

```bash
kubectl -n webpserver top pods
```

---

## API base path

By default, the API endpoints and Swagger UI are served at the root path (`/`). To serve them under a different path (for example, when behind an API gateway or reverse proxy), add `QUARKUS_HTTP_ROOT_PATH` to the `ConfigMap`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: webpserver-config
  namespace: webpserver
data:
  QUARKUS_HTTP_ROOT_PATH: "/api/v1"
  # ... other keys
```

With this setting:
- API endpoints respond to `https://example.com/api/v1/`
- Swagger UI is at `https://example.com/api/v1/q/swagger-ui`
- OpenAPI spec is at `https://example.com/api/v1/q/openapi`

Then re-apply:

```bash
kubectl apply -f kubernetes/deployment.yaml
kubectl -n webpserver rollout restart deployment/webpserver
```

This affects both the actual endpoints and the documentation automatically.

---

## Updating to a new version

```bash
kubectl -n webpserver set image deployment/webpserver \
  webpserver=ghcr.io/ibethus/webpserver:1.2.3

kubectl -n webpserver rollout status deployment/webpserver
```

Or update the `image:` field in `deployment.yaml` and re-apply.

---

## Uninstalling

```bash
kubectl delete namespace webpserver
```

**This also deletes the PersistentVolumeClaim.** Whether the underlying PersistentVolume and its data are preserved depends on the `reclaimPolicy` of your storage class. Back up your images before uninstalling.

---

## Debug logging

By default the application logs at `INFO` level. To enable verbose `DEBUG` logs (auth decisions per request, cache HIT/MISS detail, rate-limit checks), add the following key to the `ConfigMap` in `deployment.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: webpserver-config
  namespace: webpserver
data:
  QUARKUS_LOG_CATEGORY__IO_WEBPSERVER__LEVEL: "DEBUG"
  # ... other keys
```

Then re-apply and restart the deployment:

```bash
kubectl apply -f kubernetes/deployment.yaml
kubectl -n webpserver rollout restart deployment/webpserver
```

> **Note:** `DEBUG` logs include individual auth outcomes and per-IP rate-limit results. Do not enable it permanently in production as it is verbose and may expose client IP addresses in logs.

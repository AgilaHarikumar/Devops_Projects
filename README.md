# Microservices on Kubernetes — Demo Project

A hands-on demo covering the full lifecycle of deploying Java microservices to Kubernetes: containerization, service-to-service communication, ingress routing, secrets management, rolling updates, and Helm packaging.

## Architecture

```
                    ┌─────────────────┐
   Browser ───────► │  Nginx Ingress   │
                    └────────┬─────────┘
                    /users │  │ /orders
                    ┌───────┘  └────────┐
                    ▼                    ▼
            user-service          order-service
           (Deployment, 3          (Deployment, 1
            replicas, v2.0)          replica, v1.0)
                                          │
                                    reads API_KEY
                                    from a Secret
```

- **`user-service`** — Spring Boot REST API exposing `/users` and `/health`. Runs as 3 replicas.
- **`order-service`** — Spring Boot REST API exposing `/orders`, which calls `user-service` internally via Kubernetes' built-in Service DNS (`http://user-service:8081/users`). Also exposes `/orders/secret-check` to prove Secret injection.
- **Nginx Ingress** — single external entry point, routes by path (`/users` → user-service, `/orders` → order-service).
- **Secret** — `API_KEY` injected into `order-service` as an environment variable, never hardcoded in YAML or source code.
- **Helm** — both services and the Ingress are packaged as Helm charts, so the whole stack installs/upgrades with a single command per chart instead of raw `kubectl apply`.

## Requirements Checklist

| Requirement | Status |
|---|---|
| Java microservices | ✅ |
| Docker | ✅ |
| Kubernetes (Deployment + Service) | ✅ |
| Service-to-service communication | ✅ |
| Ingress routing | ✅ |
| Secret | ✅ |
| Rolling update | ✅ |
| Helm | ✅ |
| Service registry (Eureka) | Skipped — Kubernetes' native Service DNS already handles discovery |

## Prerequisites

- Docker Desktop
- Minikube
- kubectl
- Helm 3
- Java 21 + Maven (via `./mvnw`, bundled in each project)

```bash
docker --version
kubectl version --client
minikube version
helm version
```

## Project Structure

```
userservice/           # user-service source, Dockerfile
order-service/         # order-service source, Dockerfile
k8s-demo/
├── user-service-chart/
├── order-service-chart/
└── ingress-chart/
```

---

## Step-by-Step: From Zero to Running Demo

### 1. Start the cluster

```bash
minikube start
minikube addons enable ingress
kubectl get nodes                     # confirm node is Ready
kubectl get pods -n ingress-nginx     # confirm ingress controller is Running
```

### 2. Build each service's Docker image

For each service (`user-service`, `order-service`):

```bash
cd <service-folder>
docker build --platform linux/amd64 -t <service-name>:<tag> .
```

> **Why `--platform linux/amd64`:** Minikube's node runs amd64 Linux regardless of host CPU. Building without this flag can produce a binary for the wrong architecture, causing `exec format error` at container startup.

### 3. Load the image into Minikube

Minikube runs its own isolated image store — a locally built image isn't visible to it until loaded explicitly.

```bash
docker save -o <service-name>.tar <service-name>:<tag>
minikube image load <service-name>.tar
minikube image ls | grep <service-name>   # verify
```

> **Why save-to-tar instead of `minikube image load <name>` directly:** on some Docker Desktop setups (particularly Windows with the containerd image store), loading by name can silently load a corrupted/mismatched image. Loading from an explicit `.tar` snapshot is more reliable.

### 4. Create the Secret (order-service only)

```bash
kubectl create secret generic order-service-secret \
  --from-literal=API_KEY=demo-api-key-12345
```

> This is the manual, pre-Helm version. The Helm chart (Step 7) templates this instead.

### 5. Deploy with plain kubectl (first pass, before Helm)

```bash
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl get pods -w      # watch until Running
```

Repeat for both services. Key manifest details:

- **Labels/selectors**: `Deployment.spec.selector.matchLabels` must match `Deployment.spec.template.metadata.labels`, and the `Service.spec.selector` must match the same labels — this is what lets the Service auto-discover pods.
- **`imagePullPolicy: Never`**: required for locally-loaded images; without it, Kubernetes tries to pull from Docker Hub and fails with `ImagePullBackOff`.
- **`readinessProbe`**: checks `/health` before routing traffic to a pod. Tune `initialDelaySeconds`/`failureThreshold` generously if your environment has slow container startup (we needed up to 60s delay / 10 retries during testing).

### 6. Apply the Ingress

```bash
kubectl apply -f ingress.yaml
minikube tunnel     # leave running in its own terminal — simulates a cloud load balancer
```

```yaml
# ingress.yaml (simplified — no rewrite needed since app paths already match)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: microservices-ingress
spec:
  ingressClassName: nginx
  rules:
    - http:
        paths:
          - path: /users
            pathType: Prefix
            backend:
              service: { name: user-service, port: { number: 8081 } }
          - path: /orders
            pathType: Prefix
            backend:
              service: { name: order-service, port: { number: 8082 } }
```

> **Lesson learned:** an earlier version used `rewrite-target: /$2` with regex capture groups, which stripped the path down to `/` and caused 404s. Since both apps already expose `/users` and `/orders` natively, a plain `Prefix` match with no rewrite is simpler and correct.

### 7. Verify

```bash
curl http://localhost/users
curl http://localhost/orders
curl http://localhost/orders/secret-check
```

Expected:
```
alice, bob, charlie
Orders placed by: alice, bob, charlie
API_KEY is present, starts with: demo****
```

### 8. Rolling update

```bash
# 1. Change code (e.g. return "... [v2]"), bump replicas, add readinessProbe
# 2. Build + load the new image tag
docker build --platform linux/amd64 -t user-service:2.0 .
docker save -o user-service-v2.tar user-service:2.0
minikube image load user-service-v2.tar

# 3. Update deployment.yaml's image tag to 2.0, then:
kubectl apply -f deployment.yaml
kubectl rollout status deployment/user-service
kubectl get pods -l app=user-service -w   # watch old pods terminate, new ones appear

# Rollback if needed:
kubectl rollout undo deployment/user-service
kubectl rollout history deployment/user-service
```

`maxUnavailable: 1` / `maxSurge: 1` in the Deployment's `rollingUpdate` strategy ensures at least 2 of 3 replicas stay available throughout — zero downtime during the update.

### 9. Package with Helm

Scaffold, then simplify (delete unused boilerplate: `hpa.yaml`, `serviceaccount.yaml`, generated `ingress.yaml`, `tests/`):

```bash
cd k8s-demo
helm create user-service-chart
helm create order-service-chart
mkdir -p ingress-chart/templates
```

Each chart's `templates/deployment.yaml` and `service.yaml` mirror the plain manifests from Step 5, with hardcoded values replaced by `{{ .Values.* }}` placeholders sourced from that chart's `values.yaml`. `order-service-chart` additionally includes a `secret.yaml` template using `stringData` so the API key comes from `values.yaml` instead of a manual `kubectl create secret` command.

Dry-run the templates before installing (catches syntax errors early):

```bash
helm template ./user-service-chart
```

Install each chart:

```bash
kubectl delete ingress microservices-ingress   # remove the manually-applied one first —
                                                 # Nginx's admission webhook rejects two
                                                 # Ingress objects claiming the same path

helm install user-service-release ./user-service-chart
helm install order-service-release ./order-service-chart
helm install ingress-release ./ingress-chart
```

Upgrade without touching YAML:

```bash
helm upgrade user-service-release ./user-service-chart --set replicaCount=2
helm history user-service-release
helm uninstall <release-name>   # clean teardown
```

---

## Debugging Reference

Issues actually hit while building this, and how they were diagnosed:

| Symptom | Cause | Fix |
|---|---|---|
| `exec format error` in logs | Image architecture mismatch with Minikube's node | Rebuild with `docker build --platform linux/amd64`, reload via `.tar` |
| `CrashLoopBackOff`, app starts but exits immediately | Missing `spring-boot-starter-web` dependency — no embedded server to keep the process alive | Add the dependency to `pom.xml`, rebuild |
| `ImagePullBackOff` | Missing `imagePullPolicy: Never` on a locally-loaded image | Add the field to the container spec |
| Readiness probe `connection refused` repeatedly | App startup took 60–90s in a slow environment, but probe's `initialDelaySeconds` was too short | Increase `initialDelaySeconds` and `failureThreshold` |
| Ingress returns 404 with `"path":"/"` | `rewrite-target` regex was stripping the path too aggressively | Remove the rewrite annotation; use plain `pathType: Prefix` |
| `helm install` fails: path already defined in another ingress | A manually-applied Ingress (`kubectl apply`) still existed with the same rules | `kubectl delete ingress <old-name>` before installing the Helm-managed one |
| `helm install` fails: "cannot reuse a name that is still in use" | A previous failed install left a release record behind | `helm uninstall <release-name>`, then retry |
| `minikube image rm` fails: "must force" | A running container was still using that image | `kubectl delete pod <pod>` first, then remove the image |

## Useful Commands Cheat Sheet

```bash
kubectl get pods / svc / deployment / ingress / secrets
kubectl describe pod <name>              # events, image, probe config
kubectl logs <pod> [--previous]          # app-level errors
kubectl rollout status/history/undo deployment/<name>
kubectl scale deployment <name> --replicas=N

helm list
helm template ./<chart>                  # dry-run, no install
helm install/upgrade/uninstall <release> ./<chart>
helm history <release>

minikube image load <file.tar>
minikube tunnel                          # required for Ingress access on Minikube
```

# Kubernetes Notes — samplehello project

## The full pipeline, in order

1. **Dockerfile** (project root) — instructions for turning the compiled JAR into a Docker image.
2. `docker build -t samplehello:latest .` — builds the image locally from the Dockerfile.
3. `docker tag samplehello:latest zhenzongchoo/samplehello:latest` — adds a second name to the *same* image, in the `username/name:tag` format Docker Hub requires. Does not rebuild anything.
4. `docker push zhenzongchoo/samplehello:latest` — uploads the image to Docker Hub so it has a public address Kubernetes can pull from.
5. `deployment.yaml` — tells Kubernetes to run 2 copies (pods) of that image, and keep them alive.
6. `service.yaml` — gives those pods a single stable address, exposed to `localhost:8080`.
7. `kubectl apply -f <file>` — makes the cluster's real state match what a YAML file describes.

## deployment.yaml, field by field

```yaml
apiVersion: apps/v1        # fixed pairing: Deployment always uses apps/v1
kind: Deployment            # "keep N copies of my app running, replace them if they die"
metadata:
  name: samplehello-deployment   # name of this Deployment object (used in kubectl commands)
  labels:
    app: samplehello        # tag on the Deployment object itself, for organizing/filtering
spec:                        # desired behavior of the Deployment
  replicas: 2                # how many identical pod copies to keep running
  selector:
    matchLabels:
      app: samplehello        # "any pod labeled app:samplehello belongs to me"
                               # MUST match the label under template.metadata.labels below
  template:                    # blueprint used to create each pod
    metadata:
      labels:
        app: samplehello        # label stamped onto every pod made from this blueprint
    spec:                        # spec of the POD itself (different from the spec above)
      containers:
        - name: samplehello-container   # name of the container within the pod
          image: zhenzongchoo/samplehello:latest   # the actual image to pull and run
          ports:
            - containerPort: 8080   # port the app listens on INSIDE the container
```

Key gotcha: `spec:` appears twice at different indentation — outer one configures the
Deployment (replica count etc.), inner one (under `template:`) configures the Pod.

## service.yaml, field by field

```yaml
apiVersion: v1                # Service is a "core" resource, just v1 (no group prefix)
kind: Service                  # "expose a set of pods at one stable address"
metadata:
  name: samplehello-service     # name of this Service object
spec:
  type: LoadBalancer             # on Docker Desktop's local Kubernetes, this auto-exposes
                                   # the service at localhost — easiest option for local testing
  selector:
    app: samplehello               # send traffic to any pod labeled app:samplehello
                                     # (same label the Deployment's pods carry)
  ports:
    - port: 8080                    # port the Service itself listens on (what you connect to)
      targetPort: 8080               # port on the pod to forward to (must match containerPort)
```

Key gotcha: `ports:` must be a sibling of `selector:` (both directly under `spec:`), not
nested inside `selector:`.

## The YAML indentation rule

`key: value` needs a space after the colon, or YAML won't treat it as a key-value pair.
Indentation level = "who owns this line" — a line's parent is whatever line above it has
one less indent level.

## Command cheat sheet

| Command | What it does |
|---|---|
| `docker images` | List images stored locally, with their Image IDs |
| `docker login` | Authenticate your terminal with Docker Hub |
| `docker build -t <name>:<tag> .` | Build an image from the Dockerfile in the current folder |
| `docker tag <source> <target>` | Give an existing image an additional name (instant, no rebuild) |
| `docker push <name>:<tag>` | Upload an image to Docker Hub |
| `kubectl config current-context` | Show which cluster kubectl is currently talking to |
| `kubectl get nodes` | List the machines available to the cluster |
| `kubectl apply -f <file>.yaml` | Create/update cluster resources to match the YAML file |
| `kubectl get deployments` | Show Deployments and their ready/available replica counts |
| `kubectl get pods` | Show individual pods and their status |
| `kubectl get services` | Show Services and their external addresses |
| `kubectl delete -f <file>.yaml` | Remove the resources defined in that file |

## Glossary

- **Pod** — the smallest unit Kubernetes runs; one or more containers sharing network/storage. Disposable — gets replaced (with a new name/IP) if it dies.
- **Deployment** — manages a set of identical pods, keeps the desired replica count alive.
- **Service** — a stable network address in front of a changing set of pods, found via label selector.
- **Label / selector** — labels are tags on objects (`app: samplehello`); selectors are queries that match objects by their labels. This is how Deployments find their pods and Services find their pods.
- **Registry (e.g. Docker Hub)** — remote storage for images, so they're reachable from machines other than the one that built them.

---

## Adding Postgres as a stateful K8s resource

Unlike `samplehello`, a database can't just be disposable pods — the whole point is
the data survives even when a pod is replaced. That needs three new resource kinds
plus one Deployment/Service pair matching the earlier pattern.

### postgres-secret.yaml

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
type: Opaque
stringData:
  POSTGRES_DB: mydb
  POSTGRES_USER: user
  POSTGRES_PASSWORD: password
```

- `type: Opaque` — generic key-value secret (the default type).
- `stringData:` — plaintext keys/values; Kubernetes base64-encodes them internally
  when storing (encoding, not encryption). Using `stringData` instead of `data:`
  means you don't have to base64-encode the values yourself first.
- Key names (`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`) match exactly what
  the official `postgres` image looks for on startup to initialize itself.

### postgres-pvc.yaml

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

- A PVC is a *request* for storage, not the storage itself — Docker Desktop's
  default StorageClass auto-provisions the actual disk behind it.
- `ReadWriteOnce` — only one pod at a time can mount it. Fine here since Postgres
  only ever runs as 1 replica.
- **Gotcha:** Docker Desktop's default StorageClass uses
  `volumeBindingMode: WaitForFirstConsumer` — the PVC will sit in `STATUS: Pending`
  until a pod that actually mounts it exists. That's expected, not broken; it
  flips to `Bound` the moment `postgres-deployment.yaml` is applied.

### postgres-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres-deployment
  labels:
    app: postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres-container
          image: postgres:16
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: postgres-secret
          volumeMounts:
            - name: postgres-storage
              mountPath: /var/lib/postgresql/data
      volumes:
        - name: postgres-storage
          persistentVolumeClaim:
            claimName: postgres-pvc
```

- `replicas: 1` — you don't horizontally scale a single database this way; each
  replica would get its own separate disk and diverge. (Multi-instance Postgres
  needs a different, more advanced setup — StatefulSets — not used here.)
- `envFrom.secretRef.name` — loads every key in `postgres-secret` as env vars into
  the container. This is how the `postgres` image receives its init credentials.
- `volumes:` (pod-level) — declares the pod has access to `postgres-pvc`, nicknamed
  `postgres-storage`.
- `volumeMounts:` (container-level) — mounts that volume at
  `/var/lib/postgresql/data`, the exact path the `postgres` image writes its data
  files to. This is what makes the data outlive any single pod.

### postgres-service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres-service
spec:
  type: ClusterIP
  selector:
    app: postgres
  ports:
    - port: 5432
      targetPort: 5432
```

- `type: ClusterIP` (vs. `samplehello-service`'s `LoadBalancer`) — only reachable
  from *inside* the cluster (other pods), not exposed to `localhost`. A database
  shouldn't be reachable directly from outside the cluster.
- The Service **name** (`postgres-service`) becomes a DNS hostname other pods can
  resolve — this is exactly what the app uses to find Postgres (see below).

### Wiring the app to Postgres

Three changes on the app side, all needed together:

1. **`pom.xml`** — add `spring-boot-starter-data-jpa` (Spring's ORM layer) and the
   `org.postgresql:postgresql` driver (`scope: runtime` — the low-level piece that
   actually speaks Postgres's wire protocol; JPA needs a driver underneath it).

2. **`application.properties`**:
   ```properties
   spring.datasource.url=jdbc:postgresql://postgres-service:5432/mydb
   spring.datasource.username=${POSTGRES_USER}
   spring.datasource.password=${POSTGRES_PASSWORD}
   spring.jpa.hibernate.ddl-auto=update
   ```
   `postgres-service` in the URL is the Service's DNS name, resolved automatically
   inside the cluster — stable even if the Postgres pod behind it gets replaced.
   `${POSTGRES_USER}`/`${POSTGRES_PASSWORD}` are placeholders Spring resolves from
   environment variables at startup, instead of hardcoding the password in a
   plaintext file.

3. **`deployment.yaml` (the app's, not Postgres's)** — needs its own
   `envFrom: [{secretRef: {name: postgres-secret}}]` block added to
   `samplehello-container`. **Gotcha:** each pod is an isolated box — env vars
   injected into the *Postgres* pod aren't visible inside the *app* pod. The same
   Secret has to be referenced by both Deployments separately, once so Postgres can
   initialize with those credentials, and again so the app can read them to know
   how to log in.

Entity/repository/controller code lives in
`src/main/java/com/dockerExample/demo/{entity,repository,controller}` (all
lowercase, standard Java package convention) — `Greeting` entity,
`GreetingRepository` (a bodiless interface — Spring Data JPA generates the
implementation at startup), and two test-only `GET` endpoints
(`/greetings/add/{message}`, `/greetings`) used instead of a proper `POST` purely
so everything's testable from a browser URL.

**Local build gotcha:** `mvnw.cmd clean package` runs the auto-generated
`DemoApplicationTests.contextLoads` test, which boots the *entire* app including a
real Postgres connection. `postgres-service` only resolves inside the cluster, so
this test fails locally with `UnknownHostException`. Build with
`-DskipTests` to skip it.

**Redeploy note:** the image tag is `:latest`, which Kubernetes always re-pulls on
pod (re)creation (default `imagePullPolicy: Always` for the `:latest` tag,
vs. `IfNotPresent` for any other tag). Combined with `deployment.yaml`'s spec
having changed (`envFrom` added), `kubectl apply -f deployment.yaml` triggers a
full rolling update — visible as the pods getting an entirely new name suffix
(new ReplicaSet).

**Verified end-to-end:** killed the running Postgres pod
(`kubectl delete pod <name>`) after saving a `Greeting` row through the app; once
Kubernetes recreated the pod, `/greetings` still returned the saved row — proof
the data lives in the PVC, independent of any specific pod.

## Glossary (Postgres additions)

- **Secret** — a K8s object storing sensitive key-value data (credentials, tokens).
  Values are base64-encoded internally (encoding, not encryption).
- **PersistentVolumeClaim (PVC)** — a request for storage that outlives any single
  pod. On Docker Desktop, auto-provisioned by the default StorageClass.
- **StorageClass** — defines *how* storage gets provisioned for PVCs (which
  provisioner, binding mode, etc.). Docker Desktop's default uses
  `WaitForFirstConsumer` binding.
- **ClusterIP** — a Service type only reachable from inside the cluster, not
  exposed to the host machine. The default for internal-only backends like a
  database.
- **envFrom / secretRef** — a Deployment field that injects every key from a named
  Secret as environment variables into a container.

---

## Readiness & liveness probes

By default Kubernetes only knows a container's *process* is running — not whether
the app inside it is actually working. Probes are active health checks that fix
that blind spot, and the two kinds do genuinely different things on failure:

- **Liveness probe** — "is this container stuck/broken?" On failure, Kubernetes
  **kills and restarts the container** (same mechanism as the manual pod-delete
  test, just automatic).
- **Readiness probe** — "is this container ready for real traffic *right now*?" On
  failure, Kubernetes does **not** restart anything — it just **removes the pod
  from the Service's routing list** until it passes again. Pod keeps running,
  just benched.

### deployment.yaml additions

```yaml
          livenessProbe:
            httpGet:
              path: /hello
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /hello
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
```

- `httpGet.path` / `port` — Kubernetes makes a real HTTP GET from inside the
  container's network; 200–399 = healthy, anything else (or no response) = failed.
- `initialDelaySeconds` — grace period after container start before checking at
  all, so normal startup time doesn't count as a failure.
- `periodSeconds` — how often to keep re-checking, forever, for the container's
  whole life.
- Readiness is usually checked sooner/more often than liveness (`5`/`5` vs.
  `10`/`10`) — a traffic-routing decision should react fast, but restarting a
  whole container is disruptive enough that it shouldn't trigger on a brief blip.
- **Indentation gotcha:** `initialDelaySeconds`/`periodSeconds` must be siblings
  of `httpGet:`, not nested inside it — easy to fat-finger since `httpGet` is
  itself indented one level in.

### What a passing probe looks like

`kubectl describe pod <name>` shows `Ready: True` under `Conditions`, and the
`Liveness:`/`Readiness:` lines echo back your config. **No "probe succeeded"
Events ever appear** — Kubernetes only logs an Event on failure (a state change
worth flagging), since successful checks happen silently on a loop forever.
Absence of `Unhealthy` events = working as intended.

### What a failing readiness probe looks like (tested by pointing path at
### `/does-not-exist`)

- `kubectl get pods` — broken pod shows `STATUS: Running` but `READY: 0/1`.
- The Deployment temporarily runs **one extra pod** above `replicas: 2` during the
  rollout (controlled by `maxSurge`, default 25%) — trying to bring up a
  replacement before removing an old one. Since the replacement never passes
  readiness, the rollout stalls there indefinitely, refusing to touch the last
  known-good pods rather than risk zero healthy replicas.
- `kubectl describe pod <broken-pod>` → `Events:` shows repeating
  `Warning  Unhealthy  ...  Readiness probe failed: HTTP probe failed with
  statuscode: 404`.
- `kubectl get endpoints samplehello-service` — the broken pod's IP is **absent**
  from the list; only the still-healthy old pods' IPs appear. Proof the pod is
  fully excluded from live traffic while still running in the background.
- Reverting the path and re-`apply`ing cleans everything back up automatically —
  the broken ReplicaSet's pod gets removed once it's no longer the target state.

## Glossary (probes additions)

- **Liveness probe** — health check that restarts the container if it fails.
- **Readiness probe** — health check that pulls the pod out of Service traffic
  routing (without restarting it) if it fails.
- **maxSurge** — how many extra pods above the desired `replicas` count a
  Deployment may create during a rolling update, to try bringing up new pods
  before removing old ones. Defaults to 25%.
- **Endpoints** — the live list of pod IPs a Service is actually routing traffic
  to right now, filtered by readiness. `kubectl get endpoints <service-name>`.

---

## ConfigMaps

Same idea as a Secret — externalize a value instead of hardcoding it in a
Deployment — but for values that **aren't sensitive**. Stored as plain text (no
base64 quirk), safe to view in `kubectl get`/logs/git history.

Judgment call worth remembering: of `postgres-secret`'s original three keys
(`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`), only the last two are
actually sensitive. A database *name* isn't a credential — knowing it's called
`mydb` gives an attacker nothing without the password. Moved it out into a
ConfigMap.

### postgres-configmap.yaml

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: postgres-config
data:
  POSTGRES_DB: mydb
```

- `data:` (not `stringData:`) — ConfigMaps don't have Secret's base64-encoding
  distinction, so there's only one field name for values.

### Wiring it in

Both `postgres-deployment.yaml` and `deployment.yaml` (the app) got a second
`envFrom` entry alongside the existing `secretRef`:

```yaml
          envFrom:
            - secretRef:
                name: postgres-secret
            - configMapRef:
                name: postgres-config
```

`configMapRef` works exactly like `secretRef`, just for a ConfigMap instead of a
Secret — multiple `envFrom` entries just get merged into one environment.

`application.properties` swapped its hardcoded `mydb` for `${POSTGRES_DB}`, now
resolved from the ConfigMap:
```properties
spring.datasource.url=jdbc:postgresql://postgres-service:5432/${POSTGRES_DB}
```

**Gotcha (re-confirmed):** pushing a new image to Docker Hub changes nothing in a
running cluster by itself — Kubernetes only pulls a new image when *creating* a
pod for that spec, not when the registry content changes. A rollout needs an
actual spec diff (like the `envFrom` addition here) to happen at all; with no
YAML change, you'd need `kubectl rollout restart deployment <name>` to force
fresh pods with no other trigger.

Verified end-to-end: rebuilt/pushed the image, `kubectl apply -f .` (applying the
whole `k8s/` directory in one shot — much less tedious than naming all 6 files),
rollout completed cleanly, and `/greetings` responded correctly using the
ConfigMap-sourced database name.

## Glossary (ConfigMap additions)

- **ConfigMap** — like a Secret, but for non-sensitive config values; plain text,
  no base64 encoding.
- **configMapRef** (under `envFrom`) — injects every key from a named ConfigMap as
  environment variables into a container, same pattern as `secretRef`.

---

## Resource requests & limits

Without this, `kubectl describe pod` always showed `QoS Class: BestEffort` —
Kubernetes' way of saying "this container declared zero resource needs," which
means the scheduler has no idea how much room to actually reserve for it.

### deployment.yaml addition

```yaml
          resources:
            requests:
              cpu: 250m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

- **`requests`** — matters only at **scheduling time**. The scheduler checks
  "does any node have at least this much free?" and only places the pod there if
  so. It's a reservation/guarantee, not a runtime cap — once running, the
  container can use *more* than its request (up to its limit) if the node has
  spare capacity at that moment.
- **`limits`** — the hard ceiling, enforced continuously the whole time the
  container runs, regardless of what else is happening on the node.
- **CPU vs. memory behave differently past the limit:** CPU just gets
  **throttled** (slowed down, keeps running). Memory gets **`OOMKilled`**
  (Out-Of-Memory killed — the container is actually terminated and restarted).
- Units: CPU in **millicores** (`1000m` = 1 full core, so `250m` = a quarter
  core). Memory in **mebibytes** (`Mi` = binary megabytes, Kubernetes convention
  vs. decimal `M`).
- If no node anywhere has enough free capacity to satisfy a pod's `requests`, that
  pod just sits in `STATUS: Pending` — the Deployment does **not** create extra
  replicas to compensate; `replicas:` is always a fixed count you declared, never
  auto-adjusted by fit problems. (Separate mechanism, not covered yet: Horizontal
  Pod Autoscaler, for auto-scaling replica count based on load.)

### QoS (Quality of Service) classes

Ranks pods by how protected their resource allocation is, i.e. which get
sacrificed first if a node runs short on memory:

- **BestEffort** — no requests/limits set at all (the default before this
  lesson). Evicted first.
- **Burstable** — requests set, but limits absent or higher than requests (what
  `samplehello` is now, since `250m`/`256Mi` requests ≠ `500m`/`512Mi` limits).
  Middle ground.
- **Guaranteed** — requests exactly equal limits for both CPU and memory. Evicted
  last, highest protection.

Verified: after applying, `kubectl describe pod` on a new `samplehello` pod
showed `QoS Class: Burstable` and a populated `Limits:`/`Requests:` block
matching the YAML. Also visible in the same pod's Events: a few early
`Warning Unhealthy ... connection refused` entries while Spring Boot was still
booting, self-resolved once the app was actually up — the readiness probe from
the earlier lesson correctly holding the pod out of traffic during that window,
with zero extra effort.

## Glossary (resource limits additions)

- **requests** — guaranteed minimum resources a container gets; used by the
  scheduler to decide node placement. Not a runtime cap.
- **limits** — hard ceiling on resource usage, enforced continuously at runtime.
- **millicores (`m`)** — CPU unit; `1000m` = 1 full CPU core.
- **OOMKilled** — a container terminated for exceeding its memory limit
  (Out-Of-Memory).
- **QoS (Quality of Service) class** — `BestEffort` / `Burstable` / `Guaranteed`,
  ranking pods by protection level when a node is short on resources.
- **Horizontal Pod Autoscaler (HPA)** — separate mechanism (not yet used here)
  that auto-adjusts a Deployment's replica count based on observed load.

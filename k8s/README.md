# Deploying to Scaleway Kubernetes (Kapsule)

Walkthrough for standing up the bot on a fresh Scaleway account: create the
cluster and registry, build and push the image, then apply the manifests in
this directory.

Prerequisites: [`scw` CLI](https://github.com/scaleway/scaleway-cli) logged
in (`scw init`), `kubectl`, and `docker`.

## 1. Create the Kubernetes cluster

```bash
scw k8s cluster create name=split version=1.32.7 \
  pools.0.name=default pools.0.node-type=DEV1-M pools.0.size=1

scw k8s kubeconfig install <cluster-id>
```

Wait for the cluster to reach `ready` status (`scw k8s cluster get
<cluster-id>`) before continuing.

## 2. Create the container registry

```bash
scw registry namespace create name=split
```

Note the namespace's endpoint from the output (e.g. `rg.fr-par.scw.cloud/split`).

Log Docker in to it:

```bash
docker login rg.fr-par.scw.cloud/split -u nologin --password-stdin <<< "$SCW_SECRET_KEY"
```

## 3. Build and push the image

```bash
docker build -t rg.fr-par.scw.cloud/split/split-telegram-bot:latest .
docker push rg.fr-par.scw.cloud/split/split-telegram-bot:latest
```

If your registry namespace or region differs from `split` / `fr-par`, adjust
the tag here and the `image:` field in `deployment.yaml` to match.

## 4. Create the namespace and secret

```bash
kubectl apply -f k8s/namespace.yaml

kubectl create secret generic split-telegram \
  --namespace split \
  --from-literal=TELEGRAM_BOT_TOKEN="<token from BotFather>"
```

`secret.example.yaml` documents the secret's shape — don't `apply` it as-is
or commit a copy with a real token.

## 5. Apply the storage and deployment

```bash
kubectl apply -f k8s/pvc.yaml
kubectl apply -f k8s/deployment.yaml
```

Check it came up:

```bash
kubectl -n split get pods
kubectl -n split logs -f deployment/split-telegram-bot
```

You should see `Bot started, polling for updates...` in the logs.

## Redeploying after a code change

```bash
docker build -t rg.fr-par.scw.cloud/split/split-telegram-bot:<new-tag> .
docker push rg.fr-par.scw.cloud/split/split-telegram-bot:<new-tag>
kubectl -n split set image deployment/split-telegram-bot \
  split-telegram-bot=rg.fr-par.scw.cloud/split/split-telegram-bot:<new-tag>
```

The `Recreate` strategy in `deployment.yaml` is required, not incidental:
the bot long-polls Telegram for updates, and two replicas polling at once
would race for the same updates. Never scale this deployment beyond 1
replica or switch it to `RollingUpdate`.

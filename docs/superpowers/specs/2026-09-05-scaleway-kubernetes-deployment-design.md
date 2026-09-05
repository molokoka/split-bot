# Scaleway Kubernetes deployment design

## Purpose

Run the Telegram bot on a Scaleway Kapsule cluster instead of a developer's
machine, with the SQLite database persisted across restarts.

## Design

- **Image**: multi-stage `Dockerfile` — `eclipse-temurin:17-jdk-jammy` runs
  `./gradlew :telegram:installDist` (the existing Gradle `application`
  plugin distribution, no new build plugins), then
  `eclipse-temurin:17-jre-jammy` runs the installed scripts. Built and
  pushed manually with `docker` + `scw` — no CI/CD.
- **Deployment**: `replicas: 1`, `strategy: Recreate`. The bot long-polls
  Telegram for updates; a second concurrent poller would race for the same
  updates, so it can never run with more than one replica or a rolling
  update strategy.
- **Storage**: a `PersistentVolumeClaim` on the `scw-bssd-retain` storage
  class (Retain reclaim policy), mounted at `/data`, with `SPLIT_DB_PATH`
  pointed at `/data/split.db`. Retain (over the default `scw-bssd`) so the
  bot's only datastore survives an accidental PVC deletion.
- **Secret**: `TELEGRAM_BOT_TOKEN` as a plain Kubernetes Secret, created
  manually with `kubectl create secret` and referenced via
  `secretKeyRef`. `k8s/secret.example.yaml` documents its shape without a
  real token.
- **Networking**: no Service or Ingress — the bot only makes outbound calls
  to the Telegram API.
- **Namespace**: dedicated `split` namespace.

Manifests live in `k8s/` at the repo root, with `k8s/README.md` walking
through cluster/registry creation (cluster and registry don't exist yet)
through to a running deployment and redeploys.

## Out of scope

- CI/CD image build/push automation.
- Managed secret stores (Scaleway Secret Manager).
- Metrics, alerting, or log shipping.
- Backups of the SQLite volume beyond the PVC's own retention.

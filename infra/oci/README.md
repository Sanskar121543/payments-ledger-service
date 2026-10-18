# Phase 8: one Always Free VM on Oracle Cloud

The same `compose.yaml` stack that runs on a laptop, on one Ampere (Arm) VM from Oracle's Always Free tier.
Nothing is publicly reachable except SSH from one IP address. The dashboard, Grafana and the API stay on the VM's
loopback interface, exactly as locally, and you reach them through an SSH tunnel.

```mermaid
flowchart LR
  you["Your laptop<br/>browser on localhost:18088"] -- "SSH tunnel<br/>(port 22, your IP only)" --> vm
  you -- "deploy.sh: git archive over SSH" --> vm
  subgraph oci["Oracle Cloud, home region"]
    subgraph vcn["VCN 10.42.0.0/16, public subnet"]
      vm["VM.Standard.A1.Flex, 4 OCPU / 24 GB<br/>Ubuntu 24.04 arm64<br/>Docker Compose: nginx, app x3,<br/>Postgres, Kafka, Prometheus, Grafana"]
    end
  end
  vm -- "first boot: Docker packages,<br/>later: image pulls" --> net["Ubuntu, Docker Hub, Maven Central"]
```

The VM never talks to GitHub: `deploy.sh` sends a commit from your machine over SSH, so the repo can stay private.

## What Terraform creates, and why it stays free

| Resource | Why | Always Free allowance it uses |
|---|---|---|
| VCN, internet gateway, route table, subnet | A network for the VM with a route out (for packages and image pulls) | Free, no allowance |
| Security list | Allows SSH from `ssh_allowed_cidr` only. The VCN's default list is taken over with no rules | Free, no allowance |
| `VM.Standard.A1.Flex`, 4 OCPU / 24 GB | Runs the full profile (about 5.2 GB of container limits) with room to spare | All of the 4 OCPU / 24 GB of A1 |
| 100 GB boot volume | OS, images, Postgres and Kafka data. Block volume speed grows with size, so 100 GB has twice the IOPS of 50 GB | Half of the 200 GB of block storage, which leaves room for the two free AMD micro VMs |
| Ephemeral public IP | So you can SSH in | Free |

Not used, on purpose: load balancers, NAT gateways, reserved IPs, managed databases or any other billed service.
The variables refuse more than 4 OCPUs, 24 GB of memory or a 200 GB boot volume.

## Before you start

- An Oracle Cloud account, with an API key in `~/.oci/config` (`oci iam region list --profile DEFAULT` works).
- Terraform 1.6 or newer, and an SSH key pair. The defaults expect `~/.ssh/payments-ledger-oci` and `.pub`:
  `ssh-keygen -t ed25519 -f ~/.ssh/payments-ledger-oci`.
- `cp terraform.tfvars.example terraform.tfvars`, then set `tenancy_ocid` (the `tenancy=` line of `~/.oci/config`) and
  `ssh_allowed_cidr` (your IP from `curl https://checkip.amazonaws.com`, plus `/32`). The file is gitignored.

## Deploy

Always Free Arm capacity is often sold out, so the usual way is to let a script wait for it:

```bash
cd infra/oci
./wait-and-apply.sh    # checks every 5 minutes; when a domain has room: plan, apply, then deploy.sh
```

It writes the availability domain it used into `terraform.tfvars` (so later applies keep the VM where it is),
refreshes `ssh_allowed_cidr` if your IP changed while it waited, and if the room is gone before the VM is placed,
goes back to waiting. A failed attempt creates only the network, which is free.

By hand, if `./capacity-check.sh` already shows a domain as `AVAILABLE`:

```bash
terraform init
terraform plan -out=phase8.tfplan   # with availability_domain_number set to that domain
terraform apply phase8.tfplan       # creates the 7 resources
./deploy.sh                         # waits for Docker on the VM, sends HEAD, builds, starts, runs the smoke test
```

The first `deploy.sh` makes the VM's secrets (Postgres and Grafana passwords, the demo API key) on the VM, so they
never appear in Terraform state, user data or the repo. Then:

```bash
eval "$(terraform output -raw demo_api_key)"     # the API key generated on the VM
eval "$(terraform output -raw tunnel)"           # leave running, then open http://localhost:18088 and http://localhost:13000
```

On the VM (`eval "$(terraform output -raw ssh)"`), everything in the RUNBOOK works the same way from
`/opt/payments-ledger-service`, including `scripts/smoke.sh` and `scripts/load-test.sh 100 60s`. Load-test results
record the VM's hardware and the commit `deploy.sh` sent.

## Update the VM

Commit, then `./deploy.sh` (or `./deploy.sh <tag-or-sha>`). Uncommitted changes are not sent. The data and `.env`
stay; the stack restarts on the new images. To switch between `full` and `lite`, change `compose_profile` in
`terraform.tfvars`, `terraform apply` (no resource changes) and `./deploy.sh`.

## When Oracle stops the VM

Oracle may stop an Always Free VM it considers idle: over 7 days, CPU (95th percentile), network and memory all
below 20%. An idle demo VM can qualify. It is stopped, not deleted, and the data stays. Start it in the console or
with `oci compute instance action --action START --instance-id <id>`: a systemd unit starts the stack at boot, as it
does after any reboot.

## Your IP changed

SSH stops connecting. Update `ssh_allowed_cidr` in `terraform.tfvars` and `terraform apply`: only the security list changes.

## Remove everything

```bash
ssh-keygen -R "$(terraform output -raw public_ip)"   # forget the host key: a new VM may get the same IP
terraform destroy                                    # deletes the VM, its boot volume and the network
```

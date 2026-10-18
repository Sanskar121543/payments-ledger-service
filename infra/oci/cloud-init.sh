#!/bin/bash
# First-boot setup, run once by cloud-init as root: Docker and an empty app directory. Progress: /var/log/payments-ledger-setup.log
# The code arrives afterwards over SSH (infra/oci/deploy.sh), so nothing on the VM needs access to the repo.
set -euxo pipefail
exec > >(tee -a /var/log/payments-ledger-setup.log) 2>&1

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl openssl rsync

# Docker Engine and the Compose plugin from Docker's own apt repository (the Ubuntu package lags behind).
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
usermod -aG docker ubuntu

install -d -o ubuntu -g ubuntu /opt/payments-ledger-service
echo "Docker is ready. Run infra/oci/deploy.sh on your machine to send the code."

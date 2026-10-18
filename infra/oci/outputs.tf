output "public_ip" {
  description = "The VM's public address. Only SSH from ssh_allowed_cidr reaches it."
  value       = oci_core_instance.ledger.public_ip
}

output "ssh_key" {
  description = "Private key for the ubuntu user (read by deploy.sh)."
  value       = local.ssh_private
}

output "compose_profile" {
  description = "Compose profile the VM runs (read by deploy.sh)."
  value       = var.compose_profile
}

output "ssh" {
  description = "Log in to the VM."
  value       = "ssh -i ${local.ssh_private} ubuntu@${oci_core_instance.ledger.public_ip}"
}

output "tunnel" {
  description = "Forward the dashboard (18088), Grafana (13000), kafbat-ui (18090) and Prometheus (19091) to this machine."
  value       = "ssh -i ${local.ssh_private} -N -L 18088:127.0.0.1:8088 -L 13000:127.0.0.1:3000 -L 18090:127.0.0.1:8090 -L 19091:127.0.0.1:9091 ubuntu@${oci_core_instance.ledger.public_ip}"
}

output "demo_api_key" {
  description = "Prints the API key generated on the VM, for the dashboard's Connect box."
  value       = "ssh -i ${local.ssh_private} ubuntu@${oci_core_instance.ledger.public_ip} grep ^DEMO_API_KEY= /opt/payments-ledger-service/.env"
}

output "image" {
  description = "The Ubuntu image the VM was created from."
  value       = data.oci_core_images.ubuntu_arm.images[0].display_name
}

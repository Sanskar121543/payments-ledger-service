locals {
  compartment_id = coalesce(var.compartment_ocid, var.tenancy_ocid)
  shape          = "VM.Standard.A1.Flex"
  tags           = { project = "payments-ledger-service" }
  ssh_private    = trimsuffix(var.ssh_public_key_path, ".pub")
}

data "oci_identity_availability_domains" "all" {
  compartment_id = var.tenancy_ocid
}

# Newest Canonical Ubuntu 24.04 build for Arm. Changes to it later do not replace the VM (see lifecycle).
data "oci_core_images" "ubuntu_arm" {
  compartment_id           = local.compartment_id
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = local.shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"

  filter {
    name   = "display_name"
    values = ["^Canonical-Ubuntu-24\\.04-aarch64-[0-9.]+-[0-9]+$"]
    regex  = true
  }
}

resource "oci_core_instance" "ledger" {
  compartment_id      = local.compartment_id
  availability_domain = data.oci_identity_availability_domains.all.availability_domains[var.availability_domain_number - 1].name
  display_name        = "payments-ledger"
  shape               = local.shape
  freeform_tags       = local.tags

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_in_gbs
  }

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_arm.images[0].id
    boot_volume_size_in_gbs = var.boot_volume_size_in_gbs
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
    hostname_label   = "ledger"
  }

  # First boot installs Docker only. The code arrives over SSH with deploy.sh, so the VM never needs repo access.
  metadata = {
    ssh_authorized_keys = trimspace(file(pathexpand(var.ssh_public_key_path)))
    user_data           = filebase64("${path.module}/cloud-init.sh")
  }

  instance_options {
    are_legacy_imds_endpoints_disabled = true
  }

  # The boot volume holds the ledger's data; it goes with the VM on destroy so nothing is left behind.
  preserve_boot_volume = false

  lifecycle {
    ignore_changes = [source_details[0].source_id, metadata["user_data"]]
  }
}

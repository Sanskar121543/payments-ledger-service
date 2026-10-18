resource "oci_core_vcn" "ledger" {
  compartment_id = local.compartment_id
  cidr_blocks    = ["10.42.0.0/16"]
  display_name   = "payments-ledger"
  dns_label      = "ledger"
  freeform_tags  = local.tags
}

resource "oci_core_internet_gateway" "ledger" {
  compartment_id = local.compartment_id
  vcn_id         = oci_core_vcn.ledger.id
  display_name   = "payments-ledger"
  enabled        = true
  freeform_tags  = local.tags
}

resource "oci_core_route_table" "public" {
  compartment_id = local.compartment_id
  vcn_id         = oci_core_vcn.ledger.id
  display_name   = "payments-ledger-public"
  freeform_tags  = local.tags

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.ledger.id
  }
}

# Every VCN comes with a default security list that allows SSH from anywhere. It is not attached to the
# subnet below, and taking it over with no ingress rules means it opens nothing even if someone attaches it.
resource "oci_core_default_security_list" "locked" {
  manage_default_resource_id = oci_core_vcn.ledger.default_security_list_id
  display_name               = "payments-ledger-default-locked"
  freeform_tags              = local.tags
}

resource "oci_core_security_list" "ssh_only" {
  compartment_id = local.compartment_id
  vcn_id         = oci_core_vcn.ledger.id
  display_name   = "payments-ledger-ssh-only"
  freeform_tags  = local.tags

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  # The only way in. The dashboard, Grafana and the API stay on the VM's loopback and are reached
  # through an SSH tunnel: the demo API key is public in the repo, so the API must not be.
  ingress_security_rules {
    protocol = "6"
    source   = var.ssh_allowed_cidr

    tcp_options {
      min = 22
      max = 22
    }
  }

  # "Fragmentation needed", so path MTU discovery works for the SSH session.
  ingress_security_rules {
    protocol = "1"
    source   = var.ssh_allowed_cidr

    icmp_options {
      type = 3
      code = 4
    }
  }
}

resource "oci_core_subnet" "public" {
  compartment_id             = local.compartment_id
  vcn_id                     = oci_core_vcn.ledger.id
  cidr_block                 = "10.42.1.0/24"
  display_name               = "payments-ledger-public"
  dns_label                  = "public"
  route_table_id             = oci_core_route_table.public.id
  security_list_ids          = [oci_core_security_list.ssh_only.id]
  prohibit_public_ip_on_vnic = false
  freeform_tags              = local.tags
}

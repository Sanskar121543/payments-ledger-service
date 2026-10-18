variable "oci_profile" {
  description = "Profile in ~/.oci/config that holds the API key credentials."
  type        = string
  default     = "DEFAULT"
}

variable "region" {
  description = "Your tenancy's home region. Always Free Arm capacity exists only there."
  type        = string
  default     = "us-ashburn-1"
}

variable "tenancy_ocid" {
  description = "Tenancy OCID (the tenancy= line in ~/.oci/config). Used for availability domains and image lookup."
  type        = string
}

variable "compartment_ocid" {
  description = "Compartment for every resource. Defaults to the tenancy's root compartment."
  type        = string
  default     = null
}

variable "availability_domain_number" {
  description = "1, 2 or 3. Pick one that infra/oci/capacity-check.sh reports as AVAILABLE."
  type        = number
  default     = 1

  validation {
    condition     = contains([1, 2, 3], var.availability_domain_number)
    error_message = "availability_domain_number must be 1, 2 or 3."
  }
}

# Always Free covers 1,500 OCPU hours and 9,000 GB hours of Ampere A1 a month per tenancy, which Oracle equates to
# 2 OCPUs and 12 GB (it was 4 and 24 until 2026). These limits keep this configuration inside it; the tenancy's own
# limits are higher, and anything above the free amount is billed or, on a free-only account, shut down.
variable "ocpus" {
  description = "Ampere A1 OCPUs (Always Free: at most 2 across the tenancy)."
  type        = number
  default     = 2

  validation {
    condition     = var.ocpus >= 1 && var.ocpus <= 2 && floor(var.ocpus) == var.ocpus
    error_message = "ocpus must be 1 or 2 to stay within Always Free."
  }
}

variable "memory_in_gbs" {
  description = "Ampere A1 memory in GB (Always Free: at most 12 across the tenancy)."
  type        = number
  default     = 12

  validation {
    condition     = var.memory_in_gbs >= 6 && var.memory_in_gbs <= 12
    error_message = "memory_in_gbs must be from 6 to 12: the stack uses about 3 GB under load, Always Free stops at 12."
  }
}

# Block volume performance grows with size (Balanced: 60 IOPS and 480 KB/s per GB), so 100 GB gives Postgres and
# Kafka twice the IOPS of the 50 GB minimum, and leaves 100 GB free for Oracle's two Always Free AMD micro VMs.
variable "boot_volume_size_in_gbs" {
  description = "Boot volume size (Always Free: 200 GB of block storage in total)."
  type        = number
  default     = 100

  validation {
    condition     = var.boot_volume_size_in_gbs >= 50 && var.boot_volume_size_in_gbs <= 200
    error_message = "boot_volume_size_in_gbs must be from 50 (the image minimum) to 200 (the Always Free total)."
  }
}

variable "ssh_public_key_path" {
  description = "Public half of the SSH key for the ubuntu user. The private half stays on your machine."
  type        = string
  default     = "~/.ssh/payments-ledger-oci.pub"
}

variable "ssh_allowed_cidr" {
  description = "The only source allowed to reach SSH, e.g. your public IP as a.b.c.d/32."
  type        = string

  validation {
    condition     = can(cidrhost(var.ssh_allowed_cidr, 0)) && tonumber(split("/", var.ssh_allowed_cidr)[1]) >= 24
    error_message = "ssh_allowed_cidr must be a CIDR of /24 or narrower; SSH is never opened to the whole internet."
  }
}

variable "compose_profile" {
  description = "Read by deploy.sh. full: nginx + 3 app instances + Grafana. lite: 1 app instance, no Grafana."
  type        = string
  default     = "full"

  validation {
    condition     = contains(["lite", "full"], var.compose_profile)
    error_message = "compose_profile must be lite or full."
  }
}

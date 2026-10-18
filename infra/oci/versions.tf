terraform {
  required_version = ">= 1.6"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 9.10"
    }
  }
}

# Credentials come from ~/.oci/config (the profile below). Nothing secret lives in this directory.
provider "oci" {
  config_file_profile = var.oci_profile
  region              = var.region
}

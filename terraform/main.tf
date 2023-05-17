terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "3.5.0"
    }
  }
}

variable "project_id" {
  type    = string
  default = "dst-python-test-1"
}

variable "vm_zone" {
  type    = string
  default = "us-central1-a"
}

variable "vm_name" {
  type    = string
  default = "terraform-instance"
}

provider "google" {
  credentials = file("C:/Users/somers/Downloads/dst-python-test-1-f7176cdbd7d8.json")
  project     = var.project_id
  region      = "us-central1"
  zone        = var.vm_zone
}

resource "google_compute_instance" "vm_instance" {
  name         = var.vm_name
  machine_type = "f1-micro"
  boot_disk {
    initialize_params {
      image = "projects/ubuntu-os-cloud/global/images/ubuntu-1804-bionic-v20230405"
    }
  }

  network_interface {
    network            = "https://www.googleapis.com/compute/v1/projects/shared-vpc-interconnect-202004/global/networks/default-network"
    subnetwork         = "https://www.googleapis.com/compute/v1/projects/shared-vpc-interconnect-202004/regions/us-central1/subnetworks/vmbuild-subnet"
    subnetwork_project = "shared-vpc-interconnect-202004"
  }
}

output "public_ip" {
  value = google_compute_instance.vm_instance.network_interface.0.network_ip
}

output "connect_string" {

  value = "gcloud compute ssh --zone ${var.vm_zone} ${var.vm_name}  --tunnel-through-iap --project ${var.project_id}"
}
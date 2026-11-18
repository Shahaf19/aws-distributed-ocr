#!/bin/bash
# AMI recipe: the software that rarely changes is baked into the image, so instances start fast.
# Base: Canonical's Ubuntu 24.04 (amd64). Runs as root, e.g. as the user-data of the instance the AMI is built from.
# Our jar is NOT baked in: Manager/Worker user-data downloads the current one from S3 at boot.
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update -y
# Java 17 runtime; Tesseract 5 + English data (Tess4J 5.20 needs Tesseract 5). The -dev packages provide the
# unversioned libtesseract.so / libleptonica.so names that Tess4J's native loader looks for.
apt-get install -y openjdk-17-jre-headless tesseract-ocr tesseract-ocr-eng libtesseract-dev libleptonica-dev \
                   curl unzip

# AWS CLI v2 (the spec lists it; Ubuntu 24.04 has no apt package for it), used by user-data to fetch the jar
curl -sSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
unzip -q /tmp/awscli.zip -d /tmp
/tmp/aws/install
rm -rf /tmp/aws /tmp/awscli.zip

mkdir -p /opt/ocr
java -version
tesseract --version
aws --version
echo "AMI setup done; tessdata: /usr/share/tesseract-ocr/5/tessdata"

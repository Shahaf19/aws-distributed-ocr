#!/bin/bash
# One-time check, appended after ami-setup.sh when building the AMI: fetch the jar the way Manager/Worker
# user-data will, OCR one known image (the spec sample's "3D Text"), and report to S3 so no SSH is needed.
# Afterwards the jar is removed again, so the AMI snapshot stays code-free.
set +e   # report failures instead of stopping
BUCKET=dsp-ocr-a1
aws s3 cp "s3://$BUCKET/app/aws-distributed-ocr.jar" /opt/ocr/app.jar
java -cp /opt/ocr/app.jar com.bgu.ocr.worker.OcrCheck \
     http://markdotto.com/uploads/2011/01/3d-text.jpg /usr/share/tesseract-ocr/5/tessdata > /tmp/ocr-check.txt 2>&1
echo "exit code: $?" >> /tmp/ocr-check.txt
rm -f /opt/ocr/app.jar

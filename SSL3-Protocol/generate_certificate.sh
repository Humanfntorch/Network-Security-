#!/bin/bash

# Generate a new key pair for server, and a self-signed certificate.
keytool -selfcert -alias serverKey -keyalg RSA -keysize 2048 \
  -validity 1 -keystore serverkeystore.jks -storepass serverPassword -keypass serverPassword -dname "CN=Server, OU=Server, O=Server, L=Server, ST=Server, C=ut"

# Export the certificate associated with the key to a file named "serverCertificate.cer"
keytool -exportcert -alias serverKey -keystore serverkeystore.jks \
  -file serverCertificate.cer -storepass serverPassword

# Generate a new key pair for the client, and a self-signed certificate.
keytool -selfcert -alias clientKey -keyalg RSA -keysize 2048 \
  -validity 1 -keystore clientkeystore.jks -storepass clientPassword -keypass clientPassword -dname "CN=Client, OU=Client, O=Client, L=Client, ST=Client, C=ut"

# Export the certificate associated with the key to a file named "clientCertificate.cer"
keytool -exportcert -alias clientKey -keystore clientkeystore.jks \
  -file clientCertificate.cer -storepass clientPassword


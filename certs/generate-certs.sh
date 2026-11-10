#!/usr/bin/env bash
# Dev-only TLS material for the local stack. Creates a private CA and one server certificate signed by it,
# then copies two PKCS12 stores into the modules' src/main/resources/tls/ (read by spring.ssl.bundle.jks.*):
#
#   server.p12      server certificate + private key   -> eureka-server, order-service, product-service
#                   (the modules that SERVE HTTPS; server.ssl.bundle: server)
#   truststore.p12  the CA certificate only             -> every module that CALLS an HTTPS endpoint
#                   (eureka-server, admin-server, gateway-service, user-service, order-service, product-service:
#                    bundle "trust"; zipkin-server: JVM default trust store, see DevCaTrust)
#
# The server certificate's SubjectAltName must contain every host name a client puts in the URL, or hostname
# verification fails ("No subject alternative names matching IP address ... found"):
#   localhost, 127.0.0.1, 127.0.0.2  -> the three Eureka nodes (eureka.server.instances)
#   127.0.0.1                        -> order/product register eureka.instance.ip-address 127.0.0.1
# Add more with EXTRA_SAN, e.g. EXTRA_SAN="DNS:orders.internal,IP:10.0.0.5" ./certs/generate-certs.sh
#
# Re-running regenerates everything (new CA): restart all services afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/certs/out"                       # CA private key lives here; git-ignored
PASSWORD="${TLS_STORE_PASSWORD:-changeit}"  # must match TLS_KEYSTORE_PASSWORD / TLS_TRUSTSTORE_PASSWORD (default changeit)
DAYS="${TLS_DAYS:-825}"
SAN="DNS:localhost,IP:127.0.0.1,IP:127.0.0.2${EXTRA_SAN:+,$EXTRA_SAN}"

SERVER_MODULES=(eureka-server order-service product-service)
TRUST_MODULES=(eureka-server admin-server gateway-service user-service order-service product-service zipkin-server)

rm -rf "$OUT" && mkdir -p "$OUT"
cd "$OUT"

# 1. CA: self-signed, may only sign certificates (basicConstraints CA:TRUE)
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days "$DAYS" \
  -keyout ca.key -out ca.crt -subj "/CN=learning-spring-cloud dev CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"

# 2. Server key + CSR, signed by the CA with the SANs above (serverAuth = usable as a TLS server certificate)
openssl req -newkey rsa:2048 -nodes -sha256 -keyout server.key -out server.csr -subj "/CN=localhost"
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -sha256 -days "$DAYS" -out server.crt \
  -extfile <(printf "subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n" "$SAN")

# 3. PKCS12 stores. server.p12 carries the chain (server + CA) under alias "server";
#    truststore.p12 holds only the CA as a trusted entry (keytool, so Java sees it as trustedCertEntry).
openssl pkcs12 -export -name server -inkey server.key -in server.crt -certfile ca.crt \
  -out server.p12 -passout "pass:$PASSWORD"
keytool -importcert -noprompt -alias dev-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASSWORD" >/dev/null

for m in "${SERVER_MODULES[@]}"; do
  mkdir -p "$ROOT/$m/src/main/resources/tls" && cp server.p12 "$ROOT/$m/src/main/resources/tls/"
done
for m in "${TRUST_MODULES[@]}"; do
  mkdir -p "$ROOT/$m/src/main/resources/tls" && cp truststore.p12 "$ROOT/$m/src/main/resources/tls/"
done

echo "SAN: $SAN"
openssl x509 -in server.crt -noout -subject -issuer -enddate
echo "CA certificate (import into a browser/OS to trust the dashboards): $OUT/ca.crt"

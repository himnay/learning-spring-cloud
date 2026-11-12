#!/usr/bin/env bash
# Dev-only TLS material for the local stack: a private CA and one certificate per service, signed by it. Every
# service authenticates with its own certificate in both directions (mutual TLS):
#   - as a server: Tomcat presents it (server.ssl.bundle)
#   - as a client: it is sent when the server asks for one (server.ssl.client-auth=want) and the
#     ClientCertificateSubjectFilter of the called service checks its subject against an allow-list
#
# Written into each module's src/main/resources/tls/ (read by the "service-identity" SSL bundle):
#   identity.p12    this service's certificate + private key (alias "identity"),
#                   subject "CN=<module>, OU=learning-spring-cloud" -> what callers' filters match on
#   truststore.p12  the CA certificate only: which peers/servers/clients this service trusts
#                   (zipkin-server gets only this one: it never presents a certificate, see DevCaTrust)
#
# Each certificate's SubjectAltName must contain every host name a client puts in the URL, or hostname
# verification fails ("No subject alternative names matching IP address ... found"):
#   localhost, 127.0.0.1, 127.0.0.2  -> the three Eureka nodes (eureka.server.instances)
#   127.0.0.1                        -> services register eureka.instance.ip-address 127.0.0.1
# Add more with EXTRA_SAN, e.g. EXTRA_SAN="DNS:orders.internal,IP:10.0.0.5" ./certs/generate-certs.sh
#
# certs/out/ (git-ignored) also gets the CA key and a "dev-cli" client certificate for curl. Its subject is in no
# allow-list, so presenting it shows the filter's 403:
#   curl --cert certs/out/dev-cli.crt --key certs/out/dev-cli.key -u admin:admin https://localhost:8761/eureka/apps
#
# Re-running regenerates everything (new CA): restart all services afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/certs/out"                       # CA private key lives here; git-ignored
PASSWORD="${TLS_STORE_PASSWORD:-changeit}"  # must match TLS_KEYSTORE_PASSWORD / TLS_TRUSTSTORE_PASSWORD (default changeit)
DAYS="${TLS_DAYS:-825}"
SAN="DNS:localhost,IP:127.0.0.1,IP:127.0.0.2${EXTRA_SAN:+,$EXTRA_SAN}"
OU="learning-spring-cloud"

SERVICES=(eureka-server order-service product-service user-service admin-server gateway-service)
TRUST_ONLY_MODULES=(zipkin-server)

rm -rf "$OUT" && mkdir -p "$OUT"
cd "$OUT"

# 1. CA: self-signed, may only sign certificates (basicConstraints CA:TRUE)
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days "$DAYS" \
  -keyout ca.key -out ca.crt -subj "/CN=learning-spring-cloud dev CA" 2>/dev/null \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"

# truststore.p12: only the CA, as a trusted entry (keytool, so Java sees it as trustedCertEntry)
keytool -importcert -noprompt -alias dev-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASSWORD" >/dev/null

# 2. One key + certificate per name, signed by the CA.
#    serverAuth + clientAuth: the same certificate is the TLS server identity and the mTLS client identity.
issue() { # issue <name> <subject> <extendedKeyUsage> [subjectAltName]
  local name=$1 subject=$2 eku=$3 san=${4:-}
  openssl req -newkey rsa:2048 -nodes -sha256 -keyout "$name.key" -out "$name.csr" -subj "$subject" 2>/dev/null
  openssl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -sha256 -days "$DAYS" \
    -out "$name.crt" 2>/dev/null -extfile <(
      printf "basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=%s\n" "$eku"
      [ -n "$san" ] && printf "subjectAltName=%s\n" "$san")
}

for m in "${SERVICES[@]}"; do
  issue "$m" "/OU=$OU/CN=$m" "serverAuth,clientAuth" "$SAN"
  # identity.p12 carries the chain (service + CA) under alias "identity"
  openssl pkcs12 -export -name identity -inkey "$m.key" -in "$m.crt" -certfile ca.crt \
    -out "$m.p12" -passout "pass:$PASSWORD"
  dir="$ROOT/$m/src/main/resources/tls"
  mkdir -p "$dir" && cp "$m.p12" "$dir/identity.p12" && cp truststore.p12 "$dir/"
  printf '%-16s %s\n' "$m" "$(openssl x509 -in "$m.crt" -noout -subject -nameopt RFC2253)"
done
for m in "${TRUST_ONLY_MODULES[@]}"; do
  mkdir -p "$ROOT/$m/src/main/resources/tls" && cp truststore.p12 "$ROOT/$m/src/main/resources/tls/"
done

# 3. curl client: trusted CA, but its subject is in no allow-list (expect 403 from the filter)
issue dev-cli "/OU=$OU/CN=dev-cli" "clientAuth"

echo "SAN: $SAN"
echo "CA certificate (import into a browser/OS to trust the dashboards): $OUT/ca.crt"

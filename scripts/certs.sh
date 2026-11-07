#!/usr/bin/env bash
# Makes a private certificate authority and certificates for mutual TLS between the ledger nodes
# and the API: ledger-1..N (node to node) and api (API to ledger).
#
#   scripts/certs.sh <out dir> [nodes=5] [days=825] [extra DNS names for every cert...]
#
# The test certificates in ledger/src/test/resources/tls were made with this script; they're for
# tests only and must never be used in a deployment. They also include "visitor", signed by the same
# CA but named neither api nor ledger-N, for testing the name checks.
set -euo pipefail

out=${1:?usage: certs.sh <out dir> [nodes] [days] [extra names...]}
nodes=${2:-5}
days=${3:-825}
shift $(( $# < 3 ? $# : 3 ))
extra=("$@")
mkdir -p "$out"
cd "$out"

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days "$days" \
  -subj "/CN=Seatline CA" -keyout ca.key -out ca.crt 2>/dev/null

issue() {
  local name=$1
  local sans="DNS:$name,DNS:localhost,IP:127.0.0.1"
  for e in "${extra[@]+"${extra[@]}"}"; do sans="$sans,DNS:$e"; done
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=$name" \
    -keyout "$name.key.tmp" -out "$name.csr" 2>/dev/null
  openssl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days "$days" \
    -extfile <(printf "subjectAltName=%s\nextendedKeyUsage=serverAuth,clientAuth\n" "$sans") \
    -out "$name.crt" 2>/dev/null
  # gRPC's Java TLS wants PKCS#8 keys
  openssl pkcs8 -topk8 -nocrypt -in "$name.key.tmp" -out "$name.key"
  rm -f "$name.key.tmp" "$name.csr"
}

for i in $(seq 1 "$nodes"); do issue "ledger-$i"; done
issue api
rm -f ca.srl
echo "certificates in $out: ca.crt, ledger-1..$nodes, api (keep ca.key somewhere safe or delete it)"

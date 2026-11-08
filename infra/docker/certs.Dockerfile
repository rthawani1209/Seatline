# Makes the ledger's TLS certificates on first start. openssl is baked in here so starting the
# stack doesn't need the internet.
FROM alpine:3.20
RUN apk add --no-cache bash openssl
COPY scripts/certs.sh /certs.sh
# only the first time; the nodes keep their identity across restarts
CMD ["sh", "-c", "[ -f /certs/ca.crt ] || (bash /certs.sh /certs 5 825 && chmod 644 /certs/*.key && rm -f /certs/ca.key)"]

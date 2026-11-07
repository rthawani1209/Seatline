package dev.seatline.ledger.server;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Mutual TLS for the ledger. Every node and the API have a certificate from the cluster's private
 * certificate authority (see scripts/certs.sh), and both sides of every connection show theirs.
 * On top of "signed by our CA", each port checks who is calling: Raft on the peer port only talks
 * to {@code ledger-N} certificates, the client port only to {@code api}, and the admin controls
 * (when switched on) take either, because the API's chaos panel drives them. So a stranger can't
 * connect at all, and the API's certificate can't be used to pretend to be a Raft leader.
 *
 * @param cert this side's certificate chain (PEM)
 * @param key its private key (PKCS#8 PEM)
 * @param ca the cluster's certificate authority (PEM)
 */
public record Tls(Path cert, Path key, Path ca) {

    public static final String API = "api";

    /** Certificates named like a ledger node. */
    public static boolean isNode(String name) {
        return name != null && name.matches("ledger-\\d+");
    }

    SslContext server() {
        try {
            return GrpcSslContexts.forServer(cert.toFile(), key.toFile())
                    .trustManager(ca.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("can't load TLS files " + this, e);
        }
    }

    public SslContext client() {
        try {
            return GrpcSslContexts.forClient().keyManager(cert.toFile(), key.toFile()).trustManager(ca.toFile()).build();
        } catch (Exception e) {
            throw new IllegalStateException("can't load TLS files " + this, e);
        }
    }

    /** Refuses calls whose client certificate's name doesn't pass {@code allowed}. */
    static ServerInterceptor only(Predicate<String> allowed) {
        return only((service, name) -> allowed.test(name));
    }

    /**
     * Refuses calls unless {@code allowed} passes for the called gRPC service and the client
     * certificate's name, for a port that serves different callers per service.
     */
    static ServerInterceptor only(BiPredicate<String, String> allowed) {
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                               ServerCallHandler<Q, R> next) {
                String name = callerName(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
                if (!allowed.test(call.getMethodDescriptor().getServiceName(), name)) {
                    call.close(Status.PERMISSION_DENIED.withDescription("certificate not allowed here"), new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(call, headers)) { };
            }
        };
    }

    /** The CN of the caller's certificate, or null. */
    static String callerName(SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            String dn = ((X509Certificate) chain[0]).getSubjectX500Principal().getName();
            for (Rdn rdn : new LdapName(dn).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return rdn.getValue().toString();
                }
            }
        } catch (SSLPeerUnverifiedException | InvalidNameException | ClassCastException e) {
            return null;
        }
        return null;
    }
}

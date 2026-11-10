package com.learning.discovery.eureka.config;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.Jersey3DiscoveryClientOptionalArgs;
import com.netflix.discovery.provider.DiscoveryJerseyProvider;
import com.netflix.discovery.shared.transport.jersey3.EurekaIdentityHeaderFilter;
import com.netflix.discovery.shared.transport.jersey3.EurekaJersey3Client;
import com.netflix.discovery.shared.transport.jersey3.EurekaJersey3ClientImpl;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.EurekaServerIdentity;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.Jersey3DynamicGZIPContentEncodingFilter;
import com.netflix.eureka.transport.Jersey3ReplicationClient;
import jakarta.ws.rs.client.Client;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The two outbound HTTPS paths of a registry node, both trusting the CA in the {@code trust} SSL bundle.
 * Neither reads {@code spring.ssl.bundle.*} on its own:
 * <ol>
 * <li><b>Own Eureka client</b> (registers this node with its peers, fetches the registry). On the server
 * classpath it uses Jersey; its SSLContext comes from {@link Jersey3DiscoveryClientOptionalArgs}, which
 * Spring Cloud otherwise fills only from {@code eureka.client.tls.*} (key/trust-store files).</li>
 * <li><b>Peer replication</b> (forwards every register/renew/cancel to the other nodes). Spring Cloud's
 * {@code RefreshablePeerEurekaNodes} builds each peer's Jersey client with the JVM's default trust store
 * (cacerts), so it can't trust a private CA. This {@link PeerEurekaNodes} builds the same client, with the
 * bundle's SSLContext. Trade-off: peer URLs are no longer re-resolved on an EnvironmentChangeEvent
 * ({@code /actuator/refresh}); the periodic peer-list update of {@link PeerEurekaNodes#start()} still runs.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
class EurekaTlsConfig {

    static final String TRUST_BUNDLE = "trust";

    @Bean
    Jersey3DiscoveryClientOptionalArgs jersey3DiscoveryClientOptionalArgs(SslBundles sslBundles) {
        Jersey3DiscoveryClientOptionalArgs args = new Jersey3DiscoveryClientOptionalArgs();
        args.setSSLContext(sslBundles.getBundle(TRUST_BUNDLE).createSslContext());
        return args;
    }

    // replaces EurekaServerAutoConfiguration#peerEurekaNodes (@ConditionalOnMissingBean)
    @Bean
    PeerEurekaNodes peerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig serverConfig,
                                    EurekaClientConfig clientConfig, ServerCodecs serverCodecs,
                                    ApplicationInfoManager applicationInfoManager, SslBundles sslBundles) {
        return new TlsPeerEurekaNodes(registry, serverConfig, clientConfig, serverCodecs, applicationInfoManager,
                sslBundles.getBundle(TRUST_BUNDLE).createSslContext());
    }

    /**
     * Same client as {@code EurekaJersey3ClientBuilder} / {@code RefreshablePeerEurekaNodes} build (Apache
     * connector, peer-node timeouts and pool sizes, full JSON codec, gzip and identity headers), except for the
     * "https" socket factory. Basic credentials in the peer URL are applied by {@link Jersey3ReplicationClient}.
     */
    static class TlsPeerEurekaNodes extends PeerEurekaNodes {

        private final SSLContext sslContext;

        TlsPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig serverConfig,
                           EurekaClientConfig clientConfig, ServerCodecs serverCodecs,
                           ApplicationInfoManager applicationInfoManager, SSLContext sslContext) {
            super(registry, serverConfig, clientConfig, serverCodecs, applicationInfoManager);
            this.sslContext = sslContext;
        }

        @Override
        protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) {
            PoolingHttpClientConnectionManager connections = new PoolingHttpClientConnectionManager(
                    RegistryBuilder.<ConnectionSocketFactory>create()
                            .register("http", PlainConnectionSocketFactory.getSocketFactory())
                            // default hostname verifier: the peer's host must be in the certificate's SAN
                            .register("https", new SSLConnectionSocketFactory(sslContext))
                            .build());
            connections.setDefaultMaxPerRoute(serverConfig.getPeerNodeTotalConnectionsPerHost());
            connections.setMaxTotal(serverConfig.getPeerNodeTotalConnections());

            ClientConfig jerseyConfig = new ClientConfig();
            jerseyConfig.register(new DiscoveryJerseyProvider(serverCodecs.getFullJsonCodec(),
                    serverCodecs.getFullJsonCodec()));
            jerseyConfig.property(ApacheClientProperties.CONNECTION_MANAGER, connections);
            jerseyConfig.property(ClientProperties.FOLLOW_REDIRECTS, Boolean.FALSE);

            EurekaJersey3Client jerseyClient = new EurekaJersey3ClientImpl(
                    serverConfig.getPeerNodeConnectTimeoutMs(),
                    serverConfig.getPeerNodeReadTimeoutMs(),
                    serverConfig.getPeerNodeConnectionIdleTimeoutSeconds(),
                    jerseyConfig);
            Client client = jerseyClient.getClient();
            client.register(new Jersey3DynamicGZIPContentEncodingFilter(serverConfig));
            client.register(new EurekaIdentityHeaderFilter(new EurekaServerIdentity(localIp())));

            String targetHost = hostFromUrl(peerEurekaNodeUrl);
            return new PeerEurekaNode(registry, targetHost == null ? "host" : targetHost, peerEurekaNodeUrl,
                    new Jersey3ReplicationClient(jerseyClient, peerEurekaNodeUrl), serverConfig);
        }

        private static String localIp() {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
    }
}

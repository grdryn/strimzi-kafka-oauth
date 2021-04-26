/*
 * Copyright 2017-2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.oauth.server.plain;

import io.strimzi.kafka.oauth.common.BearerTokenWithPayload;
import io.strimzi.kafka.oauth.common.OAuthAuthenticator;
import io.strimzi.kafka.oauth.server.JaasServerOauthValidatorCallbackHandler;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import io.strimzi.kafka.oauth.server.OAuthSaslAuthenticationException;
import io.strimzi.kafka.oauth.server.ServerConfig;
import io.strimzi.kafka.oauth.services.Services;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerValidatorCallback;
import org.apache.kafka.common.security.plain.PlainAuthenticateCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * This <em>AuthenticateCallbackHandler</em> implements 'OAuth over PLAIN' support.
 *
 * It is designed for use with the <em>org.apache.kafka.common.security.plain.PlainLoginModule</em> which provides
 * SASL/PLAIN authentication support to Kafka brokers. With this CallbackHandler installed, the client authenticating
 * with SASL/PLAIN mechanism can use the clientId and the secret, setting them as <em>username</em> and <em>password</em>
 * parameters.
 * <p>
 * Also, the client can use the access token to authenticate. In this case the client should set the <em>username</em> parameter
 * to the same principal the broker will resolve from the access token (depending on the Kafka Broker configuration this
 * is the value of 'sub' claim or one specified by 'oauth.username.claim' configuration). The <em>password</em> parameter depends on
 * whether the 'oauth.token.endpoint.uri' is configured on the server or not. If configured, the <em>password</em> parameter value
 * should be set to the constant <em>$accessToken:</em> followed by the actual access token string.
 * The <em>$accessToken:</em> prefix lets the broker know that the password should be treated as an access token.
 * If not configured, the client ID + secret (client credentials) mechanism is not available and the 'password' parameter is
 * interpreted as a raw access token without a prefix.
 * <p>
 * Allowing the use of OAuth credentials over SASL/PLAIN allows all existing Kafka client tools to authenticate to your
 * Kafka cluster even when they have no explicit OAuth support.
 * <p>
 * To install this <em>CallbackHandler</em> in your Kafka listener, specify the following in your 'server.properties':
 * </p>
 * <pre>
 *     # Declare a listener
 *     listeners=CLIENT://kafka:9092
 *
 *     # Specify whether the TCP connection is unsecured or protected with TLS
 *     #listener.security.protocol.map=CLIENT:SASL_PLAINTEXT
 *     listener.security.protocol.map=CLIENT:SASL_SSL
 *
 *     # Configure the keystore and truststore for SASL_SSL
 *     listener.name.client.ssl.keystore.lccation=/tmp/kafka/cluster.keystore.p12
 *     listener.name.client.ssl.keystore.password=keypass
 *     listener.name.client.ssl.keystore.type=PKCS12
 *     listener.name.client.ssl.truststore.location=/tmp/kafka/cluster.truststore.p12
 *     listener.name.client.ssl.truststore.password=trustpass
 *     listener.name.client.ssl.truststore.type=PKCS12
 *
 *     # Enable SASL/PLAIN authentication mechanism on your listener in addition to any others
 *     #sasl.enabled.mechanisms: PLAIN,OAUTHBEARER
 *     sasl.enabled.mechanisms: PLAIN
 *
 *     # Install the SASL/PLAIN LoginModule using per-listener sasl.jaas.config
 *     listener.name.client.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
 *         oauth.token.endpoint.uri="https://sso-server/token" \
 *         oauth.valid.issuer.uri="https://java-server" \
 *         oauth.jwks.endpoint.uri="https://java-server/certs" \
 *         oauth.username.claim="preferred_username";
 *
 *     # Install this CallbackHandler to provide custom handling of authentication
 *     listener.name.client.plain.sasl.server.callback.handler.class=io.strimzi.kafka.oauth.server.plain.JaasServerOauthOverPlainValidatorCallbackHandler
 * </pre>
 * <p>
 * There is additional <em>sasl.jaas.config</em> configuration that may need to be specified in order for this CallbackHandler to work with your authorization server.
 * </p>
 * <blockquote>
 * Note: The following configuration keys can be specified as parameters to <em>sasl.jaas.config</em> in Kafka `server.properties` file, or as
 * ENV vars in which case an all-uppercase key name is also attempted with '.' replaced by '_' (e.g. OAUTH_TOKEN_ENDPOINT_URI).
 * They can also be specified as system properties. The priority is in reverse - system property overrides the ENV var, which overrides
 * `server.properties`. When not specified as the parameters to <em>sasl.jaas.config</em>, the configuration keys will apply to all listeners.
 * </blockquote>
 * <p>
 * Optional <em>sasl.jaas.config</em> configuration:
 * </p>
 * <ul>
 * <li><em>oauth.token.endpoint.uri</em> A URL of the authorization server's token endpoint.<br>
 * The token endpoint is used to authenticate to authorization server with the <em>clientId</em> and the <em>secret</em> received over username and password parameters.
 * If set, both clientId + secret, and userId + access token are available. Otherwise only userId + access token authentication is available.
 * </li>
 * </ul>
 * <p>
 * The rest of the configuration is the same as for {@link JaasServerOauthValidatorCallbackHandler}.
 * </p>
 */
public class JaasServerOauthOverPlainValidatorCallbackHandler extends JaasServerOauthValidatorCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(JaasServerOauthOverPlainValidatorCallbackHandler.class);

    private URI tokenEndpointUri;

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {

        if (!"PLAIN".equals(saslMechanism))    {
            throw new IllegalArgumentException(String.format("Unexpected SASL mechanism: %s", saslMechanism));
        }

        ServerConfig config = parseJaasConfig(jaasConfigEntries);

        String tokenEndpoint = config.getValue(ServerPlainConfig.OAUTH_TOKEN_ENDPOINT_URI);
        // if tokenEndpoint is set, it will be used to fetch a token using username/password,
        // otherwise the password value is interpreted as a token
        if (tokenEndpoint != null) {
            try {
                this.tokenEndpointUri = new URI(tokenEndpoint);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid tokenEndpointUri: " + tokenEndpoint, e);
            }
        }
        super.configure(configs, "OAUTHBEARER", jaasConfigEntries);
        log.debug("Configured OAuth over PLAIN:\n    tokenEndpointUri: " + tokenEndpointUri);
        if (tokenEndpoint == null) {
            log.debug("tokenEndpointUri is not configured - client_credentials will not be available, password parameter of SASL/PLAIN will automatically be treated as an access token (no '$accessToken:' prefix needed)");
        }
    }

    @Override
    public void close() {
        super.close();
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
        String username = null;
        String password = null;
        org.apache.kafka.common.security.plain.PlainAuthenticateCallback cb = null;

        try {
            for (Callback callback : callbacks) {
                if (callback instanceof javax.security.auth.callback.NameCallback) {
                    username = ((javax.security.auth.callback.NameCallback) callback).getDefaultName();
                } else if (callback instanceof org.apache.kafka.common.security.plain.PlainAuthenticateCallback) {
                    password = String.valueOf(((org.apache.kafka.common.security.plain.PlainAuthenticateCallback) callback).password());
                    cb = (org.apache.kafka.common.security.plain.PlainAuthenticateCallback) callback;
                } else {
                    throw new UnsupportedCallbackException(callback);
                }
            }

            handleCallback(cb, username, password);

        } catch (OAuthSaslAuthenticationException e) {
            // Logged already
            throw e;
        } catch (UnsupportedCallbackException e) {
            handleErrorWithLogger(log, "Authentication failed due to misconfiguration", e);
        } catch (SaslAuthenticationException e) {
            handleErrorWithLogger(log, e.getMessage(), e);
        } catch (Throwable e) {
            handleErrorWithLogger(log, "Authentication failed for username: [" + username + "]", e);
        }
    }

    private void handleCallback(PlainAuthenticateCallback callback, String username, String password) throws Exception {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        if (username == null) throw new IllegalArgumentException("username == null");

        authenticate(username, password);
        callback.authenticated(true);
    }

    private void authenticate(String username, String password) throws UnsupportedCallbackException, IOException {
        final String accessTokenPrefix = "$accessToken:";

        boolean checkUsernameMatch = false;
        String accessToken;

        if (password != null && password.startsWith(accessTokenPrefix)) {
            accessToken = password.substring(accessTokenPrefix.length());
            checkUsernameMatch = true;
        } else if (password != null && tokenEndpointUri == null) {
            accessToken = password;
            checkUsernameMatch = true;
        } else {
            accessToken = OAuthAuthenticator.loginWithClientSecret(tokenEndpointUri, getSocketFactory(), getVerifier(),
                    username, password, isJwt(), getPrincipalExtractor(), null)
                    .token();
        }

        OAuthBearerValidatorCallback[] callbacks = new OAuthBearerValidatorCallback[] {new OAuthBearerValidatorCallback(accessToken)};
        super.handle(callbacks);

        OAuthBearerToken token = callbacks[0].token();
        if (token == null) {
            throw new RuntimeException("Authentication with OAuth token has failed (no token returned)");
        }

        if (checkUsernameMatch) {
            if (!username.equals(token.principalName())) {
                throw new SaslAuthenticationException("Username doesn't match the token");
            }
        }

        OAuthKafkaPrincipal kafkaPrincipal = new OAuthKafkaPrincipal(KafkaPrincipal.USER_TYPE,
                token.principalName(), (BearerTokenWithPayload) token);

        Services.getInstance().getCredentials().storeCredentials(username, kafkaPrincipal);
    }
}

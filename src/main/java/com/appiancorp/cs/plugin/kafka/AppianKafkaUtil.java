package com.appiancorp.cs.plugin.kafka;

import com.appiancorp.suiteapi.content.Content;
import com.appiancorp.suiteapi.content.ContentConstants;
import com.appiancorp.suiteapi.content.ContentService;
import com.appiancorp.suiteapi.security.external.SecureCredentialsStore;
import com.jayway.jsonpath.JsonPath;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Map;
import java.util.Properties;

import net.minidev.json.JSONArray;

public class AppianKafkaUtil {

  private static Logger LOG = Logger.getLogger(AppianKafkaUtil.class);

  public static final String SCS_TRUSTSTORE_PWD_KEY = "truststorepwd";
  public static final String SCS_KEYSTORE_PWD_KEY = "keystorepwd";
  public static final String SCS_KEYSTORE_PRIVATE_KEY_PWD_KEY = "privatekeypwd";
  public static final String SCS_KEYSTORE_SASL_USERNAME = "username";
  public static final String SCS_KEYSTORE_SASL_PASSWORD = "password";
  public static final String SCS_KEYSTORE_SASL_CLIENTID = "clientid";
  public static final String SCS_KEYSTORE_SASL_CLIENTSECRET = "clientsecret";
  public static final String SCS_KEYSTORE_SASL_TOKENURL = "tokenurl";
  public static final String SCS_KEYSTORE_SASL_GRANTTYPE = "granttype";
  public static final String TRANSACTION_TABLE_NAME = "tm_job_transaction";
  public static final String SCS_KEYSTORE_SASL_GROUPID = "groupid";

  public enum SecurityProtocol {
    PLAINTEXT,
    SSL,
    SASL_PLAINTEXT,
    SASL_SSL
  }

  public enum SaslMechanism {
    PLAIN("PLAIN", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";"),
    SCRAM_SHA_256("SCRAM-SHA-256", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";"),
    SCRAM_SHA_512("SCRAM-SHA-512", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";"),
    OAUTHBEARER("OAUTHBEARER",
            "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId=\"%s\" clientSecret=\"%s\";");

    private String name;
    private String jaasTemplate;

    SaslMechanism(String name, String jaasTemplate) {
      this.name = name;
      this.jaasTemplate = jaasTemplate;
    }

    String getJaasCfg(String username, String password) {
      return String.format(jaasTemplate, username, password);
    }
  }

  public static void setProperties(SecureCredentialsStore scs, ContentService cs, Properties props, String serverAndPorts,
                                   String securityProtocol, String saslMechanism, String scsKey, String consumerGroupId, String keyClass,
                                   String valueClass, Integer sessionTimeoutMs, Long trustStoreDoc,
                                   Long keyStoreDoc, boolean isConsumer) throws Exception {
    // Server
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, serverAndPorts);

    // Security
    props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);

    SecurityProtocol sp = SecurityProtocol.valueOf(securityProtocol);
    SaslMechanism sm = SaslMechanism.valueOf(saslMechanism);

    // Handle SSL
    switch (sp) {
      case SSL:
      case SASL_SSL:

        if (trustStoreDoc != null && trustStoreDoc > 0) {
          Content latestTrustStoreDoc = cs.getVersion(trustStoreDoc, ContentConstants.VERSION_CURRENT);
          String trustStorePath = cs.getInternalFilename(latestTrustStoreDoc.getId());
          props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, trustStorePath);
        }
        if (keyStoreDoc != null && keyStoreDoc > 0) {
          Content latestKeyStoreDoc = cs.getVersion(keyStoreDoc, ContentConstants.VERSION_CURRENT);
          String keyStorePath = cs.getInternalFilename(latestKeyStoreDoc.getId());
          props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keyStorePath);
        }

        Map<String, String> scsMap = scs.getSystemSecuredValues(scsKey);
        String trustStorePwd = scsMap.get(SCS_TRUSTSTORE_PWD_KEY);
        if (StringUtils.isNotBlank(trustStorePwd)) {
          props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, trustStorePwd);
        }
        String keyStorePwd = scsMap.get(SCS_KEYSTORE_PWD_KEY);
        if (StringUtils.isNotBlank(keyStorePwd)) {
          props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, keyStorePwd);
        }
        String privatePwd = scsMap.get(SCS_KEYSTORE_PRIVATE_KEY_PWD_KEY);
        if (StringUtils.isNotBlank(privatePwd)) {
          props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, privatePwd);
        }
        break;
    }

    // Handle SASL
    switch (sp) {
      case SASL_PLAINTEXT:
      case SASL_SSL:
        // Handle OAUTH
        if (sm.name == "OAUTHBEARER") {
          LOG.debug("Switch for OAUTHBEARER: ");

          LOG.debug("IP Address: " + getIp());
          props.put("ssl.protocol", "SSL");
          props.put("ssl.truststore.type", "JKS");
          Map<String, String> scsMap = scs.getSystemSecuredValues(scsKey);
          String clientId = scsMap.get(SCS_KEYSTORE_SASL_CLIENTID);
          String clientSecret = scsMap.get(SCS_KEYSTORE_SASL_CLIENTSECRET);
          String tokenUrl = scsMap.get(SCS_KEYSTORE_SASL_TOKENURL);
          String groupId = scsMap.get(SCS_KEYSTORE_SASL_GROUPID);
          props.put(SaslConfigs.SASL_JAAS_CONFIG, sm.getJaasCfg(clientId, clientSecret));
          props.put("sasl.oauthbearer.token.endpoint.url", tokenUrl);
          props.put("sasl.mechanism", "OAUTHBEARER");
          props.put("sasl.login.callback.handler.class",
                  "org.apache.kafka.common.security.oauthbearer.secured.OAuthBearerLoginCallbackHandler");
          props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
          // props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
          LOG.debug("clientId: " + clientId + "clientSecret: " + clientSecret);
        } else {

          Map<String, String> scsMap = scs.getSystemSecuredValues(scsKey);
          String username = scsMap.get(SCS_KEYSTORE_SASL_USERNAME);
          String password = scsMap.get(SCS_KEYSTORE_SASL_PASSWORD);

          props.put(SaslConfigs.SASL_MECHANISM, sm.name);
          props.put(SaslConfigs.SASL_JAAS_CONFIG, sm.getJaasCfg(username, password));
        }
        break;
    }
    if (isConsumer) {
      // Consumer

      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

      if (StringUtils.isNotBlank(consumerGroupId)) {
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
      }
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

      // _props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, _auto_commit_interval_ms);
      if (sessionTimeoutMs != null) {
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, (sessionTimeoutMs / 3));
      }

      if (StringUtils.isNotBlank(keyClass)) {
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

      }
      if (StringUtils.isNotBlank(valueClass)) {
         props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
      }
    } else {
      // Producer
      if (StringUtils.isNotBlank(keyClass)) {
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Class.forName(keyClass));
      }
      if (StringUtils.isNotBlank(valueClass)) {
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Class.forName(valueClass));
      }
    }

  }

  // Returns true if the message should be ignored
  public static boolean ignoreMesssage(String message, String messageFilter) {
    // Filtering out message
    try {
      Object dataObject = JsonPath.parse(message).read(messageFilter);
      LOG.trace("filtered message: " + dataObject.toString());

      // If the filter returns an Empty JSON Array, skip the message
      if (dataObject instanceof JSONArray && ((JSONArray) dataObject).size() == 0)
        return true;
      else
        return false;
    } catch (Exception e) {
      LOG.error("failed to filter: ", e);
      return false;
    }
  }

  public static String getIp() throws Exception {
    URL whatismyip = new URL("http://checkip.amazonaws.com");
    BufferedReader in = null;
    try {
      in = new BufferedReader(new InputStreamReader(
              whatismyip.openStream()));
      String ip = in.readLine();
      return ip;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public static boolean validateMessageFilter(String messageFilter) {
    try {
      JsonPath.parse("{\"test\": \"A\"}").read(messageFilter);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
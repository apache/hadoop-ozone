package org.apache.hadoop.ozone.om.init;

import org.apache.hadoop.hdds.HddsUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.SCMSecurityProtocolProtos;
import org.apache.hadoop.hdds.protocolPB.SCMSecurityProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.client.OMCertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.utils.CertificateCodec;
import org.apache.hadoop.hdds.security.x509.certificates.utils.CertificateSignRequest;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.OzoneSecurityUtil;
import org.apache.hadoop.ozone.om.OMStorage;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.cert.CertificateException;

import static org.apache.hadoop.hdds.security.x509.certificates.utils.CertificateSignRequest.getEncodedString;
import static org.apache.hadoop.ozone.OzoneConsts.RPC_PORT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_KERBEROS_KEYTAB_FILE_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_KERBEROS_PRINCIPAL_KEY;

class SecurityInitializer extends OzoneManagerInitializer {

  SecurityInitializer(OMStorage storage){
    super(storage);
  }

  SecurityInitializer login(OzoneConfiguration conf)
      throws AuthenticationException {
    OzoneSecurityUtil.login(conf,
        OZONE_OM_KERBEROS_KEYTAB_FILE_KEY,
        OZONE_OM_KERBEROS_PRINCIPAL_KEY,
        OmUtils.getOmAddress(conf).getHostName()
    );
    return this;
  }

  @Override
  void initialize(OzoneConfiguration conf) throws IOException {
    initializeSecurity(conf, storage);
    storage.persistCurrentState();
  }


  private void initializeSecurity(OzoneConfiguration conf,
      OMStorage omStore)
      throws IOException {
    LOG.info("Initializing secure OzoneManager.");

    CertificateClient certClient =
        new OMCertificateClient(new SecurityConfig(conf),
            omStore.getOmCertSerialId());
    CertificateClient.InitResponse response = certClient.init();
    LOG.info("Init response: {}", response);
    switch (response) {
    case SUCCESS:
      LOG.info("Initialization successful.");
      break;
    case GETCERT:
      getSCMSignedCert(certClient, conf, omStore);
      LOG.info("Successfully stored SCM signed certificate.");
      break;
    case FAILURE:
      LOG.error("OM security initialization failed.");
      throw new RuntimeException("OM security initialization failed.");
    case RECOVER:
      LOG.error("OM security initialization failed. OM certificate is " +
          "missing.");
      throw new RuntimeException("OM security initialization failed.");
    default:
      LOG.error("OM security initialization failed. Init response: {}",
          response);
      throw new RuntimeException("OM security initialization failed.");
    }
  }

  /**
   * Get SCM signed certificate and store it using certificate client.
   * */
  private void getSCMSignedCert(CertificateClient client,
      OzoneConfiguration config, OMStorage omStore) throws IOException {
    CertificateSignRequest.Builder builder = client.getCSRBuilder();
    KeyPair keyPair = new KeyPair(client.getPublicKey(),
        client.getPrivateKey());
    InetSocketAddress omRpcAdd;
    omRpcAdd = OmUtils.getOmAddress(config);
    if (omRpcAdd == null || omRpcAdd.getAddress() == null) {
      LOG.error("Incorrect om rpc address. omRpcAdd:{}", omRpcAdd);
      throw new RuntimeException("Can't get SCM signed certificate. " +
          "omRpcAdd: " + omRpcAdd);
    }
    // Get host name.
    String hostname = omRpcAdd.getAddress().getHostName();
    String ip = omRpcAdd.getAddress().getHostAddress();

    String subject = UserGroupInformation.getCurrentUser()
        .getShortUserName() + "@" + hostname;

    builder.setCA(false)
        .setKey(keyPair)
        .setConfiguration(config)
        .setScmID(omStore.getScmId())
        .setClusterID(omStore.getClusterID())
        .setSubject(subject)
        .addIpAddress(ip);

    LOG.info("Creating csr for OM->dns:{},ip:{},scmId:{},clusterId:{}," +
            "subject:{}", hostname, ip,
        omStore.getScmId(), omStore.getClusterID(), subject);

    HddsProtos.OzoneManagerDetailsProto.Builder omDetailsProtoBuilder =
        HddsProtos.OzoneManagerDetailsProto.newBuilder()
            .setHostName(omRpcAdd.getHostName())
            .setIpAddress(ip)
            .setUuid(omStore.getOmId())
            .addPorts(HddsProtos.Port.newBuilder()
                .setName(RPC_PORT)
                .setValue(omRpcAdd.getPort())
                .build());

    PKCS10CertificationRequest csr = builder.build();
    HddsProtos.OzoneManagerDetailsProto omDetailsProto =
        omDetailsProtoBuilder.build();
    LOG.info("OzoneManager ports added:{}", omDetailsProto.getPortsList());
    SCMSecurityProtocolClientSideTranslatorPB secureScmClient =
        HddsUtils.getScmSecurityClient(config);

    SCMSecurityProtocolProtos.SCMGetCertResponseProto response = secureScmClient.
        getOMCertChain(omDetailsProto, getEncodedString(csr));
    String pemEncodedCert = response.getX509Certificate();

    try {


      // Store SCM CA certificate.
      if(response.hasX509CACertificate()) {
        String pemEncodedRootCert = response.getX509CACertificate();
        client.storeCertificate(pemEncodedRootCert, true, true);
        client.storeCertificate(pemEncodedCert, true);
        // Persist om cert serial id.
        omStore.setOmCertSerialId(CertificateCodec.
            getX509Certificate(pemEncodedCert).getSerialNumber().toString());
      } else {
        throw new RuntimeException("Unable to retrieve OM certificate " +
            "chain");
      }
    } catch (IOException | CertificateException e) {
      LOG.error("Error while storing SCM signed certificate.", e);
      throw new RuntimeException(e);
    }
  }
}

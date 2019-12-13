package org.apache.hadoop.hdds.security.x509.certificate.utils;

import static org.apache.hadoop.hdds.HddsConfigKeys.OZONE_METADATA_DIRS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificates.utils.SelfSignedCertificate;
import org.apache.hadoop.hdds.security.x509.keys.HDDSKeyGenerator;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.cert.X509CRLEntryHolder;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


public class TestCRLCodec {

    private static OzoneConfiguration conf = new OzoneConfiguration();
    private static final String COMPONENT = "test";
    private SecurityConfig securityConfig;
    private X509CertificateHolder x509CertificateHolder;
    private KeyPair keyPair;
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private File basePath;

    @Before
    public void init() throws NoSuchProviderException,
        NoSuchAlgorithmException, IOException,
        CertificateException, OperatorCreationException {
        
        conf.set(OZONE_METADATA_DIRS, temporaryFolder.newFolder().toString());
        securityConfig = new SecurityConfig(conf);
        writeTempCert();
        x509CertificateHolder = readTempCert();
    }


    @Test
    public void testWriteCRL() throws IOException, OperatorCreationException {

        X500Name issuer = x509CertificateHolder.getIssuer();
        Date now = new Date();
        X509v2CRLBuilder builder = new X509v2CRLBuilder(issuer, now);
        builder.addCRLEntry(x509CertificateHolder.getSerialNumber(), now,
                            CRLReason.cACompromise);

        JcaContentSignerBuilder contentSignerBuilder =
            new JcaContentSignerBuilder("SHA256WithRSAEncryption");

        contentSignerBuilder.setProvider("BC");
        PrivateKey privateKey = keyPair.getPrivate();
        X509CRLHolder cRLHolder = builder.build(contentSignerBuilder.build(privateKey));

        CRLCodec crlCodec = new CRLCodec(securityConfig);
        crlCodec.writeCRL(cRLHolder,"pemcertificate.crl", true);

        X509CRLEntryHolder entryHolder =
            cRLHolder.getRevokedCertificate(BigInteger.ONE);
        assertNotNull(entryHolder);
    }

    /**
     * Test method for generating temporary cert and persisting into tmp folder
     * @throws NoSuchProviderException
     * @throws NoSuchAlgorithmException
     * @throws IOException
     * @throws CertificateException
     */
    private void writeTempCert() throws NoSuchProviderException,
        NoSuchAlgorithmException, IOException, CertificateException, OperatorCreationException {
        HDDSKeyGenerator keyGenerator =
            new HDDSKeyGenerator(conf);
        keyPair = keyGenerator.generateKey();
        X509CertificateHolder cert =
            SelfSignedCertificate.newBuilder()
                .setSubject(RandomStringUtils.randomAlphabetic(4))
                .setClusterID(RandomStringUtils.randomAlphabetic(4))
                .setScmID(RandomStringUtils.randomAlphabetic(4))
                .setBeginDate(LocalDate.now())
                .setEndDate(LocalDate.now().plus(1, ChronoUnit.DAYS))
                .setConfiguration(keyGenerator.getSecurityConfig()
                                      .getConfiguration())
                .setKey(keyPair)
                .makeCA()
                .build();
        CertificateCodec codec = new CertificateCodec(securityConfig, COMPONENT);
        String pemString = codec.getPEMEncodedString(cert);
        basePath = new File(
            String.valueOf(
                securityConfig.getCertificateLocation("scm")));

        if (!basePath.exists()) {
            Assert.assertTrue(basePath.mkdirs());
        }
        codec.writeCertificate(basePath.toPath(), "pemcertificate.crt",
                               pemString, false);
    }

    private X509CertificateHolder readTempCert()
        throws IOException, CertificateException {

        CertificateCodec codec =
            new CertificateCodec(securityConfig, COMPONENT);

        X509CertificateHolder x509CertificateHolder =
            codec.readCertificate(basePath.toPath(), "pemcertificate.crt");
        assertNotNull(x509CertificateHolder);

        return x509CertificateHolder;
    }
}

package org.apache.hadoop.ozone.om.multitenant;

import static org.apache.hadoop.ozone.OzoneConsts.OZONE_OM_RANGER_ADMIN_CREATE_GROUP_HTTP_ENDPOINT;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_OM_RANGER_ADMIN_CREATE_POLICY_HTTP_ENDPOINT;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_OM_RANGER_ADMIN_CREATE_USER_HTTP_ENDPOINT;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_OM_RANGER_ADMIN_DELETE_GROUP_HTTP_ENDPOINT;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_OM_RANGER_ADMIN_DELETE_POLICY_HTTP_ENDPOINT;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_OM_RANGER_ADMIN_DELETE_USER_HTTP_ENDPOINT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RANGER_HTTPS_ADMIN_API_PASSWD;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_OM_RANGER_HTTPS_ADMIN_API_USER;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_OM_CONNECTION_REQUEST_TIMEOUT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_OM_CONNECTION_REQUEST_TIMEOUT_DEFAULT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_OM_CONNECTION_TIMEOUT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_OM_CONNECTION_TIMEOUT_DEFAULT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_OM_IGNORE_SERVER_CERT;
import static org.apache.hadoop.ozone.om.OMConfigKeys.OZONE_RANGER_OM_IGNORE_SERVER_CERT_DEFAULT;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.commons.net.util.Base64;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.security.acl.IOzoneObj;
import org.apache.hadoop.ozone.security.acl.RequestContext;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiTenantGateKeeperRangerPlugin implements
    MultiTenantGateKeeper {
  private static final Logger LOG = LoggerFactory
      .getLogger(MultiTenantGateKeeperRangerPlugin.class);

  private static OzoneConfiguration conf;
  private static boolean ignoreServerCert = false;
  private static int connectionTimeout;
  private static int connectionRequestTimeout;
  private static String authHeaderValue;
  private static String rangerHttpsAddress;

  @Override
  public void init(Configuration configuration) throws IOException {
    conf = (OzoneConfiguration)configuration;
    rangerHttpsAddress = conf.get(OZONE_RANGER_HTTPS_ADDRESS_KEY);
    initializeRangerConnection();
  }

  private void initializeRangerConnection() {
    setupRangerConnectionConfig();
    if (ignoreServerCert) {
      setupRangerIgnoreServerCertificate();
    }
    setupRangerConnectionAuthHeader();
  }

  private void setupRangerConnectionConfig() {
    connectionTimeout = (int) conf.getTimeDuration(
        OZONE_RANGER_OM_CONNECTION_TIMEOUT,
        conf.get(
            OZONE_RANGER_OM_CONNECTION_TIMEOUT,
            OZONE_RANGER_OM_CONNECTION_TIMEOUT_DEFAULT),
        TimeUnit.MILLISECONDS);
    connectionRequestTimeout = (int)conf.getTimeDuration(
        OZONE_RANGER_OM_CONNECTION_REQUEST_TIMEOUT,
        conf.get(
            OZONE_RANGER_OM_CONNECTION_REQUEST_TIMEOUT,
            OZONE_RANGER_OM_CONNECTION_REQUEST_TIMEOUT_DEFAULT),
        TimeUnit.MILLISECONDS
    );
    ignoreServerCert = (boolean) conf.getBoolean(
        OZONE_RANGER_OM_IGNORE_SERVER_CERT,
            OZONE_RANGER_OM_IGNORE_SERVER_CERT_DEFAULT);
  }

  private void setupRangerIgnoreServerCertificate() {
    // Create a trust manager that does not validate certificate chains
    TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
          public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
          }
          public void checkClientTrusted(
              java.security.cert.X509Certificate[] certs, String authType) {
          }
          public void checkServerTrusted(
              java.security.cert.X509Certificate[] certs, String authType) {
          }
        }
    };

    try {
      SSLContext sc = SSLContext.getInstance("SSL");
      sc.init(null, trustAllCerts, new java.security.SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    } catch (Exception e) {
      LOG.info("Setting DefaultSSLSocketFactory failed.");
    }
  }

  private void setupRangerConnectionAuthHeader() {
    String userName = conf.get(OZONE_OM_RANGER_HTTPS_ADMIN_API_USER);
    String passwd = conf.get(OZONE_OM_RANGER_HTTPS_ADMIN_API_PASSWD);
    String auth = userName + ":" + passwd;
    byte[] encodedAuth =
        Base64.encodeBase64(auth.getBytes(StandardCharsets.UTF_8));
    authHeaderValue = "Basic " + new String(encodedAuth);
  }


  @Override
  public void shutdown() throws Exception {
    // TBD
  }

  @Override
  public void grantAccess(BucketNameSpace bucketNameSpace,
                          OzoneMultiTenantPrincipal user, ACLType aclType) {
    // TBD
  }

  @Override
  public void revokeAccess(BucketNameSpace bucketNameSpace,
                           OzoneMultiTenantPrincipal user, ACLType aclType) {
    // TBD
  }

  @Override
  public void grantAccess(AccountNameSpace accountNameSpace,
                          OzoneMultiTenantPrincipal user, ACLType aclType) {
    // TBD
  }

  @Override
  public void revokeAccess(AccountNameSpace accountNameSpace,
                           OzoneMultiTenantPrincipal user, ACLType aclType) {
    // TBD
  }

  public List<Pair<BucketNameSpace, ACLType>>
  getAllBucketNameSpaceAccesses(OzoneMultiTenantPrincipal user) {
    // TBD
    return null;
  }

  @Override
  public boolean checkAccess(BucketNameSpace bucketNameSpace,
                             OzoneMultiTenantPrincipal user) {
    // TBD
    return true;
  }

  @Override
  public boolean checkAccess(AccountNameSpace accountNameSpace,
                             OzoneMultiTenantPrincipal user) {
    // TBD
    return true;
  }

  @Override
  public boolean checkAccess(IOzoneObj ozoneObject, RequestContext context)
      throws OMException {
    // TBD
    return true;
  }
  private String getCreateUserJsonString(String userName,
                                         List<String> groupIDs)
      throws Exception {
    String groupIdList = groupIDs.stream().collect(Collectors.joining("\",\"",
        "",""));
    String jsonCreateUserString = "{ \"name\":\"" + userName  + "\"," +
        "\"firstName\":\"" + userName + "\"," +
        "  \"loginId\": \"" + userName + "\"," +
        "  \"password\" : \"user1pass\"," +
        "  \"userRoleList\":[\"ROLE_USER\"]," +
        "  \"groupIdList\":[\"" + groupIdList +"\"] " +
        " }";
    return jsonCreateUserString;
  }

  public String createUser(OzoneMultiTenantPrincipal principal,
                           List<String> groupIDs)
      throws Exception {
    String rangerAdminUrl =
        rangerHttpsAddress + OZONE_OM_RANGER_ADMIN_CREATE_USER_HTTP_ENDPOINT;

    String jsonCreateUserString = getCreateUserJsonString(
        principal.getFullMultiTenantPrincipalID(), groupIDs);

    HttpsURLConnection conn = makeHttpsPostCall(rangerAdminUrl,
        jsonCreateUserString,"POST", false);
    String userInfo = getReponseData(conn);
    String userIDCreated;
    try {
      JSONObject jObject = new JSONObject(userInfo.toString());
      userIDCreated = jObject.getString("id");
      System.out.println("User ID is : " + userIDCreated);
    } catch (JSONException e) {
      e.printStackTrace();
      throw e;
    }
    return userIDCreated;
  }

  private String getCreateGroupJsonString(String groupName) throws Exception {
    String jsonCreateGroupString = "{ \"name\":\"" + groupName + "\"," +
        "  \"description\":\"test\" " +
        " }";
    return jsonCreateGroupString;
  }


  public String createGroup(OzoneMultiTenantPrincipal group) throws Exception {
    String rangerAdminUrl =
        rangerHttpsAddress + OZONE_OM_RANGER_ADMIN_CREATE_GROUP_HTTP_ENDPOINT;

    String jsonCreateGroupString = getCreateGroupJsonString(
        group.getFullMultiTenantPrincipalID());

    HttpsURLConnection conn = makeHttpsPostCall(rangerAdminUrl,
        jsonCreateGroupString,
        "POST", false);
    String groupInfo = getReponseData(conn);
    String groupIdCreated;
    try {
      JSONObject jObject = new JSONObject(groupInfo.toString());
      groupIdCreated = jObject.getString("id");
      System.out.println("GroupID is : " + groupIdCreated);
    } catch (JSONException e) {
      e.printStackTrace();
      throw e;
    }
    return groupIdCreated;
  }

  public String createAccessPolicy(AccessPolicy policy) throws Exception {
    String rangerAdminUrl =
        rangerHttpsAddress + OZONE_OM_RANGER_ADMIN_CREATE_POLICY_HTTP_ENDPOINT;

    HttpsURLConnection conn = makeHttpsPostCall(rangerAdminUrl,
        policy.getPolicyJsonString(),
        "POST", false);
    String policyInfo = getReponseData(conn);
    String policyID;
    try {
      JSONObject jObject = new JSONObject(policyInfo.toString());
      policyID = jObject.getString("id");
      System.out.println("policyID is : " + policyID);
    } catch (JSONException e) {
      e.printStackTrace();
      throw e;
    }
    return policyID;
  }

  public void deleteUser(String userId) throws Exception {

    String rangerAdminUrl =
        rangerHttpsAddress + OZONE_OM_RANGER_ADMIN_DELETE_USER_HTTP_ENDPOINT
            + userId + "?forceDelete=true";

    HttpsURLConnection conn = makeHttpsPostCall(rangerAdminUrl, null,
        "DELETE", false);
    int respnseCode = conn.getResponseCode();
    if(respnseCode != 200 && respnseCode != 204) {
      throw new IOException("Couldnt delete user " + userId);
    };
  }

  public void deleteGroup(String groupId) throws Exception {

    String rangerAdminUrl =
        rangerHttpsAddress + OZONE_OM_RANGER_ADMIN_DELETE_GROUP_HTTP_ENDPOINT
            + groupId + "?forceDelete=true";

    HttpsURLConnection conn = makeHttpsPostCall(rangerAdminUrl, null,
        "DELETE", false);
    int respnseCode = conn.getResponseCode();
    if(respnseCode != 200 && respnseCode != 204) {
      throw new IOException("Couldnt delete group " + groupId);
    };
  }

  public void deletePolicy(String policyId) throws Exception {

    String rangerAdminUrl =
        rangerHttpsAddress + OZONE_OM_RANGER_ADMIN_DELETE_POLICY_HTTP_ENDPOINT
            + policyId + "?forceDelete=true";

    HttpsURLConnection conn = makeHttpsPostCall(rangerAdminUrl, null,
        "DELETE", false);
    int respnseCode = conn.getResponseCode();
    if(respnseCode != 200 && respnseCode != 204) {
      throw new IOException("Couldnt delete policy " + policyId);
    };
  }

  private String getReponseData(HttpsURLConnection urlConnection) throws IOException {
    StringBuilder response = new StringBuilder();
    try (BufferedReader br = new BufferedReader(
        new InputStreamReader(urlConnection.getInputStream(), "utf-8"))) {
      String responseLine = null;
      while ((responseLine = br.readLine()) != null) {
        response.append(responseLine.trim());
      }
      System.out.println(response.toString());
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    return response.toString();
  }

  private HttpsURLConnection makeHttpsPostCall(String urlString,
                                              String jsonInputString,
                                              String method, boolean isSpnego)
      throws IOException, AuthenticationException {

    URL url = new URL(urlString);
    HttpsURLConnection urlConnection = (HttpsURLConnection)url.openConnection();
    urlConnection.setRequestMethod(method);
    urlConnection.setConnectTimeout(connectionTimeout);
    urlConnection.setReadTimeout(connectionRequestTimeout);
    urlConnection.setRequestProperty("Accept", "application/json");
    urlConnection.setRequestProperty("Authorization", authHeaderValue);

    if ((jsonInputString !=null) && !jsonInputString.isEmpty()) {
      urlConnection.setDoOutput(true);
      urlConnection.setRequestProperty("Content-Type", "application/json;");
      try (OutputStream os = urlConnection.getOutputStream()) {
        byte[] input = jsonInputString.getBytes("utf-8");
        os.write(input, 0, input.length);
        os.flush();
      }
    }

    return urlConnection;
  }
}

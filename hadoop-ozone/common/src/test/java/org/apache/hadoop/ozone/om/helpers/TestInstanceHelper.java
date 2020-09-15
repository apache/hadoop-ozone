package org.apache.hadoop.ozone.om.helpers;

import com.google.protobuf.ByteString;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;


/**
 * Test TestInstanceHelper.
 *
 * Utility methods to create test instances of protobuf related classes
 */
public final class TestInstanceHelper {

  private  TestInstanceHelper(){
    super();
  }

  public static OzoneManagerProtocolProtos.OzoneAclInfo buildTestOzoneAclInfo(
      String aclString){
    OzoneAcl oacl = OzoneAcl.parseAcl(aclString);
    ByteString rights = ByteString.copyFrom(oacl.getAclBitSet().toByteArray());
    return OzoneManagerProtocolProtos.OzoneAclInfo.newBuilder()
        .setType(OzoneManagerProtocolProtos.OzoneAclInfo.OzoneAclType.USER)
        .setName(oacl.getName())
        .setRights(rights)
        .setAclScope(OzoneManagerProtocolProtos.
            OzoneAclInfo.OzoneAclScope.ACCESS)
        .build();
  }

  public static HddsProtos.KeyValue getDefaultTestMetadata(
      String key, String value){
    return HddsProtos.KeyValue.newBuilder()
        .setKey(key)
        .setValue(value)
        .build();
  }

  public static OzoneManagerProtocolProtos.PrefixInfo getDefaultTestPrefixInfo(
      String name, String aclString, HddsProtos.KeyValue metadata){
    return OzoneManagerProtocolProtos.PrefixInfo.newBuilder()
        .setName(name)
        .addAcls(buildTestOzoneAclInfo(aclString))
        .addMetadata(metadata)
        .build();
  }
}

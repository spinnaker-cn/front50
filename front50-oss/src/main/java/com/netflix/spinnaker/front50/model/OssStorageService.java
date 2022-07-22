package com.netflix.spinnaker.front50.model;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.front50.config.OssProperties;
import com.netflix.spinnaker.front50.exception.NotFoundException;
import com.netflix.spinnaker.security.AuthenticatedRequest;
import net.logstash.logback.argument.StructuredArguments;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * @author chen_muyi
 * @date 2022/7/13 17:23
 */
public class OssStorageService implements StorageService {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private OSSClient ossClient;
  private OssProperties ossProperties;



  private static final Logger log = LoggerFactory.getLogger(OssStorageService.class);

  public OssStorageService(OSSClient ossClient, OssProperties ossProperties) {
    this.ossClient = ossClient;
    this.ossProperties = ossProperties;
  }

  @Override
  public void ensureBucketExists() {
    try {
      ossClient.getBucketInfo(ossProperties.getBucketName());
    } catch (Exception e) {
      if (e instanceof OSSException && "NoSuchBucket".equals(((OSSException) e).getErrorCode())) {
        CreateBucketRequest createBucketRequest = new CreateBucketRequest(ossProperties.getBucketName());
        createBucketRequest.setStorageClass(StorageClass.Standard);
        createBucketRequest.setCannedACL(CannedAccessControlList.Default);
        ossClient.createBucket(createBucketRequest);
        if (ossProperties.getVersioning()){
          BucketVersioningConfiguration bucketVersioningConfiguration = new BucketVersioningConfiguration();
          bucketVersioningConfiguration.setStatus(BucketVersioningConfiguration.ENABLED);
          SetBucketVersioningRequest setBucketVersioningRequest
            = new SetBucketVersioningRequest(ossProperties.getBucketName(),bucketVersioningConfiguration);
          setBucketVersioningRequest.setVersioningConfiguration(bucketVersioningConfiguration);
          ossClient.setBucketVersioning(setBucketVersioningRequest);
        }
      } else {
        throw e;
      }
    }
  }

  @Override
  public boolean supportsVersioning() {
    return false;
  }

  @Override
  public <T extends Timestamped> T loadObject(ObjectType objectType, String objectKey) throws NotFoundException {
    OSSObject object = ossClient.getObject(ossProperties.getBucketName(), buildKey(objectType.group, objectKey, objectType.defaultMetadataFilename));
    T item = null;
    try {
      item = deserialize(object, (Class<T>) objectType.clazz);
      item.setLastModified(object.getObjectMetadata().getLastModified().getTime());
    } catch (IOException e) {
      e.printStackTrace();
    }
    return item;
  }

  @Override
  public void deleteObject(ObjectType objectType, String objectKey) {
    String key = buildKey(objectType.group, objectKey, objectType.defaultMetadataFilename);
    ossClient.deleteObject(ossProperties.getBucketName(), key);
    writeLastModified(objectType.group);
  }

  @Override
  public <T extends Timestamped> void storeObject(ObjectType objectType, String objectKey, T item) {
    try {
      item.setLastModifiedBy(AuthenticatedRequest.getSpinnakerUser().orElse("anonymous"));
      byte[] bytes = objectMapper.writeValueAsBytes(item);

      ObjectMetadata objectMetadata = new ObjectMetadata();
      objectMetadata.setContentLength(bytes.length);
      objectMetadata.setContentMD5(new String(org.apache.commons.codec.binary.Base64.encodeBase64(DigestUtils.md5(bytes))));

      ossClient.putObject(
        ossProperties.getBucketName(),
        buildKey(objectType.group, objectKey, objectType.defaultMetadataFilename),
        new ByteArrayInputStream(bytes),
        objectMetadata
      );
      writeLastModified(objectType.group);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Map<String, Long> listObjectKeys(ObjectType objectType) {
    long startTime = System.currentTimeMillis();
    ListObjectsRequest listObjectsRequest = new ListObjectsRequest(ossProperties.getBucketName(), buildTypedFolder(ossProperties.getRootFolder(), objectType.group), null, null, 500);
    List<OSSObjectSummary> objectSummaries = new ArrayList<>();
    while (true){
      ObjectListing objectListing = ossClient.listObjects(listObjectsRequest);
      if (!CollectionUtils.isEmpty(objectListing.getObjectSummaries())){
        objectSummaries.addAll(objectListing.getObjectSummaries());
      }
      if (StringUtils.isEmpty(objectListing.getNextMarker())){
        break;
      }
      listObjectsRequest.setMarker(objectListing.getNextMarker());
    }

    log.debug("Took {}ms to fetch {} object keys for {}",
      value("fetchTime", (System.currentTimeMillis() - startTime)),
      objectSummaries.size(),
      value("type", objectType));

    return objectSummaries
      .stream()
      .filter(s -> filterObjectSummary(s, objectType.defaultMetadataFilename))
      .collect(Collectors.toMap((s -> buildObjectKey(objectType, s.getKey())), (s -> s.getLastModified().getTime())));
  }

  @Override
  public <T extends Timestamped> Collection<T> listObjectVersions(ObjectType objectType, String objectKey, int maxResults) throws NotFoundException {
    if (maxResults == 1) {
      List<T> results = new ArrayList<>();
      results.add(loadObject(objectType, objectKey));
      return results;
    }
    try {
      VersionListing versionListing = ossClient.listVersions(
        new ListVersionsRequest(
          ossProperties.getBucketName(),
          buildKey(objectType.group, objectKey, objectType.defaultMetadataFilename),
          null,
          null,
          null,
          maxResults
        )
      );
      return versionListing.getVersionSummaries().stream().map(s3VersionSummary -> {
        try {
          OSSObject object = ossClient.getObject(
            new GetObjectRequest(ossProperties.getBucketName(), buildKey(objectType.group, objectKey, objectType.defaultMetadataFilename), s3VersionSummary.getVersionId())
          );
          T item = deserialize(object, (Class<T>) objectType.clazz);
          item.setLastModified(object.getObjectMetadata().getLastModified().getTime());
          return item;
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }).collect(Collectors.toList());
    } catch (Exception e) {
      log.error("get {},{} failed",objectType.group,objectKey);
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public long getLastModified(ObjectType objectType) {
    try {
      Map<String, Long> lastModified = objectMapper.readValue(
        ossClient.getObject(ossProperties.getBucketName(), buildTypedFolder(ossProperties.getRootFolder(), objectType.group) + "/last-modified.json").getObjectContent(),
        Map.class
      );
      return lastModified.get("lastModified");
    } catch (Exception e) {
      return 0L;
    }
  }


  private String buildKey(String group, String objectKey, String metadataFilename) {
    if (objectKey.endsWith(metadataFilename)) {
      return objectKey;
    }
    return (buildTypedFolder(ossProperties.getRootFolder(), group) + "/" + objectKey.toLowerCase() + "/" + metadataFilename).replace("//", "/");
  }

  private static String buildTypedFolder(String rootFolder, String type) {
    return (rootFolder + "/" + type).replaceAll("//", "/");
  }

  private <T extends Timestamped> T deserialize(OSSObject ossObject, Class<T> clazz) throws IOException {
    return objectMapper.readValue(ossObject.getObjectContent(), clazz);
  }

  private boolean filterObjectSummary(OSSObjectSummary ossObjectSummary, String metadataFilename) {
    return ossObjectSummary.getKey().endsWith(metadataFilename);
  }

  private String buildObjectKey(ObjectType objectType, String s3Key) {
    return s3Key
      .replaceAll(buildTypedFolder(ossProperties.getRootFolder(), objectType.group) + "/", "")
      .replaceAll("/" + objectType.defaultMetadataFilename, "");
  }

  private void writeLastModified(String group) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(Collections.singletonMap("lastModified", System.currentTimeMillis()));
      ObjectMetadata objectMetadata = new ObjectMetadata();
      objectMetadata.setContentLength(bytes.length);
      objectMetadata.setContentMD5(new String(org.apache.commons.codec.binary.Base64.encodeBase64(DigestUtils.md5(bytes))));

      ossClient.putObject(
        ossProperties.getBucketName(),
        buildTypedFolder(ossProperties.getRootFolder(), group) + "/last-modified.json",
        new ByteArrayInputStream(bytes),
        objectMetadata
      );
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}

package com.netflix.spinnaker.front50.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author chen_muyi
 * @date 2022/7/13 16:53
 */
@ConfigurationProperties("spinnaker.oss")
public class OssProperties {
  private String bucketName;
  private String rootFolder;
  private String endPoint;
  private String accessKeyId;
  private String accessSecretKey;
  private Integer maxKeys;
  private Boolean versioning = true;

  public String getBucketName() {
    return bucketName;
  }

  public void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  public String getEndPoint() {
    return endPoint;
  }

  public void setEndPoint(String endPoint) {
    this.endPoint = endPoint;
  }

  public String getAccessKeyId() {
    return accessKeyId;
  }

  public void setAccessKeyId(String accessKeyId) {
    this.accessKeyId = accessKeyId;
  }

  public String getAccessSecretKey() {
    return accessSecretKey;
  }

  public void setAccessSecretKey(String accessSecretKey) {
    this.accessSecretKey = accessSecretKey;
  }

  public String getRootFolder() {
    return rootFolder;
  }

  public void setRootFolder(String rootFolder) {
    this.rootFolder = rootFolder;
  }

  public Integer getMaxKeys() {
    return maxKeys;
  }

  public void setMaxKeys(Integer maxKeys) {
    this.maxKeys = maxKeys;
  }

  public Boolean getVersioning() {
    return versioning;
  }

  public void setVersioning(Boolean versioning) {
    this.versioning = versioning;
  }
}

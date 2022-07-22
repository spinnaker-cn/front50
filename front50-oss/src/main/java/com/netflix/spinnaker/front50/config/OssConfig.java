package com.netflix.spinnaker.front50.config;

import com.aliyun.oss.OSSClient;
import com.netflix.spinnaker.front50.model.OssStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author chen_muyi
 * @date 2022/7/13 17:01
 */
@Configuration
@ConditionalOnExpression("${spinnaker.oss.enabled:false}")
@EnableConfigurationProperties(value = OssProperties.class)
public class OssConfig {

  @Bean
  public OSSClient createClient(OssProperties ossProperties){
    return new OSSClient(ossProperties.getEndPoint(), ossProperties.getAccessKeyId(), ossProperties.getAccessSecretKey());
  }

  @Bean
  public OssStorageService createStorageService(OSSClient ossClient,OssProperties ossProperties){
    return new OssStorageService(ossClient, ossProperties);
  }

}

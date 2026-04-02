# 签名文件生成指南

由于系统中未安装Java，无法直接生成签名文件。请按照以下步骤操作：

## 步骤1：安装Java JDK

1. 下载并安装Java JDK 17或更高版本
   - 下载地址：https://www.oracle.com/java/technologies/downloads/
   - 选择适合您操作系统的版本

2. 配置环境变量
   - 将Java的bin目录添加到系统PATH环境变量中
   - 验证安装：打开命令行运行 `java -version`

## 步骤2：生成签名文件

在项目根目录运行以下命令：

### Debug签名
```bash
keytool -genkey -v -keystore trackmaster_debug.jks -keyalg RSA -keysize 2048 -validity 10000 -alias trackmaster_debug -dname "CN=TrackMaster, OU=TrackMaster, O=TrackMaster, L=Beijing, ST=Beijing, C=CN" -storepass trackmaster2024 -keypass trackmaster2024
```

### Release签名
```bash
keytool -genkey -v -keystore trackmaster.jks -keyalg RSA -keysize 2048 -validity 10000 -alias trackmaster -dname "CN=TrackMaster, OU=TrackMaster, O=TrackMaster, L=Beijing, ST=Beijing, C=CN" -storepass trackmaster2024 -keypass trackmaster2024
```

## 步骤3：获取SHA1指纹

```bash
# Debug签名
keytool -list -v -keystore trackmaster_debug.jks -alias trackmaster_debug -storepass trackmaster2024

# Release签名
keytool -list -v -keystore trackmaster.jks -alias trackmaster -storepass trackmaster2024
```

## 步骤4：申请高德Key

1. 访问 https://console.amap.com/dev/key/app
2. 注册/登录账号
3. 创建新应用
4. 填写应用信息，将获取的SHA1指纹粘贴到对应位置
5. 获取API Key

## 步骤5：配置项目

在 `app/build.gradle.kts` 中替换：
```kotlin
debug {
    buildConfigField("String", "AMAP_API_KEY", "\"YOUR_AMAP_DEBUG_KEY\"")
}
release {
    buildConfigField("String", "AMAP_API_KEY", "\"YOUR_AMAP_RELEASE_KEY\"")
}
```

## 临时解决方案

如果您需要立即运行项目，可以：
1. 注释掉 `app/build.gradle.kts` 中的签名配置
2. 使用默认的debug签名
3. 后续再申请正式的高德Key

## 注意事项

- 签名文件包含敏感信息，请妥善保管
- 不要将签名文件提交到版本控制系统
- 不同的签名会导致应用被视为不同的应用，影响用户升级
